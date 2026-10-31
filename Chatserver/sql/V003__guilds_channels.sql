-- =============================================================================
-- Migration: V003__guilds_channels.sql
-- Purpose  : Discord Web Clone — Guilds, Unified Channels (room_id = channel_id),
--            User Presence/Avatar Colors, Rich Messages (Replies, Edits, Soft
--            Deletes, Pins, Full-Text Search), and Neon bytea Attachment Storage.
-- Target   : PostgreSQL 14+ / Neon Serverless Postgres
-- Idempotent: YES (safe to re-run multiple times)
-- =============================================================================

BEGIN;

-- -----------------------------------------------------------------------------
-- 1. Extend public.users (Avatar Color & Presence Status)
-- -----------------------------------------------------------------------------
ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS avatar_color varchar(16) DEFAULT '#2563EB',
    ADD COLUMN IF NOT EXISTS presence_status varchar(20) DEFAULT 'ONLINE',
    ADD COLUMN IF NOT EXISTS custom_status varchar(120);

UPDATE public.users
SET avatar_color = '#2563EB'
WHERE avatar_color IS NULL;

UPDATE public.users
SET presence_status = 'ONLINE'
WHERE presence_status IS NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'users_presence_status_check'
          AND conrelid = 'public.users'::regclass
    ) THEN
        ALTER TABLE public.users
            ADD CONSTRAINT users_presence_status_check
            CHECK (presence_status IN ('ONLINE', 'IDLE', 'DND', 'OFFLINE'));
    END IF;
END $$;

-- -----------------------------------------------------------------------------
-- 2. Create public.guilds, public.guild_members, and public.guild_invites
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.guilds (
    id SERIAL PRIMARY KEY,
    name varchar(100) NOT NULL,
    icon_text varchar(10),
    owner_id integer REFERENCES public.users(id) ON DELETE SET NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS public.guild_members (
    guild_id integer NOT NULL REFERENCES public.guilds(id) ON DELETE CASCADE,
    user_id integer NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    role varchar(20) NOT NULL DEFAULT 'MEMBER',
    joined_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY (guild_id, user_id),
    CONSTRAINT guild_members_role_check CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER'))
);

CREATE TABLE IF NOT EXISTS public.guild_invites (
    code varchar(32) PRIMARY KEY,
    guild_id integer NOT NULL REFERENCES public.guilds(id) ON DELETE CASCADE,
    created_by integer REFERENCES public.users(id) ON DELETE SET NULL,
    expires_at timestamp without time zone NOT NULL,
    max_uses integer DEFAULT 0,
    uses integer DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT guild_invites_uses_check CHECK (uses >= 0 AND (max_uses IS NULL OR max_uses >= 0))
);

CREATE INDEX IF NOT EXISTS idx_guild_members_user_id
    ON public.guild_members USING btree (user_id);

CREATE INDEX IF NOT EXISTS idx_guild_invites_guild_id
    ON public.guild_invites USING btree (guild_id);

-- -----------------------------------------------------------------------------
-- 3. Unified Channel-Room Mapping (channel_id = room_id in public.rooms)
-- -----------------------------------------------------------------------------
ALTER TABLE public.rooms
    ADD COLUMN IF NOT EXISTS guild_id integer REFERENCES public.guilds(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS channel_type varchar(20) DEFAULT 'text' NOT NULL,
    ADD COLUMN IF NOT EXISTS position integer DEFAULT 0 NOT NULL,
    ADD COLUMN IF NOT EXISTS topic varchar(255) DEFAULT '';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'rooms_channel_type_check'
          AND conrelid = 'public.rooms'::regclass
    ) THEN
        ALTER TABLE public.rooms
            ADD CONSTRAINT rooms_channel_type_check
            CHECK (channel_type IN ('text', 'voice'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_rooms_guild_position
    ON public.rooms USING btree (guild_id, position ASC, id ASC);

-- Preserve real memberships without creating a shared demo guild or sample channels.
DO $$
DECLARE
BEGIN
    -- Keep guilds sequence synchronized
    IF EXISTS (SELECT 1 FROM public.guilds) THEN
        PERFORM setval(
            pg_get_serial_sequence('public.guilds', 'id'),
            (SELECT MAX(id) FROM public.guilds),
            true
        );
    END IF;

    -- Backfill guild memberships only where a real guild is already assigned.
    INSERT INTO public.guild_members (guild_id, user_id, role)
    SELECT DISTINCT
        r.guild_id,
        rm.user_id,
        CASE
            WHEN UPPER(COALESCE(rm.role, 'MEMBER')) IN ('OWNER', 'ADMIN') THEN UPPER(rm.role)
            ELSE 'MEMBER'
        END
    FROM public.room_members rm
    JOIN public.rooms r ON r.id = rm.room_id
    WHERE rm.user_id IS NOT NULL AND r.guild_id IS NOT NULL
    ON CONFLICT (guild_id, user_id) DO NOTHING;

    -- Restore room access only for existing memberships in already-created guilds.
    INSERT INTO public.room_members (room_id, user_id, role)
    SELECT r.id, gm.user_id, gm.role
    FROM public.rooms r
    JOIN public.guild_members gm ON gm.guild_id = r.guild_id
    WHERE r.guild_id IS NOT NULL
      AND COALESCE(r.is_private, false) = false
    ON CONFLICT (room_id, user_id) DO NOTHING;
END $$;

-- -----------------------------------------------------------------------------
-- 4. Extend public.messages for Rich Chat Features
-- -----------------------------------------------------------------------------
ALTER TABLE public.messages
    ADD COLUMN IF NOT EXISTS reply_to_id integer REFERENCES public.messages(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS edited_at timestamp without time zone,
    ADD COLUMN IF NOT EXISTS deleted_at timestamp without time zone,
    ADD COLUMN IF NOT EXISTS pinned boolean DEFAULT false NOT NULL,
    ADD COLUMN IF NOT EXISTS search_vector tsvector;

CREATE OR REPLACE FUNCTION public.messages_search_vector_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.search_vector := to_tsvector('simple', coalesce(NEW.content, ''));
    RETURN NEW;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_trigger
        WHERE tgname = 'trg_messages_search_vector'
          AND tgrelid = 'public.messages'::regclass
    ) THEN
        CREATE TRIGGER trg_messages_search_vector
            BEFORE INSERT OR UPDATE OF content ON public.messages
            FOR EACH ROW
            EXECUTE FUNCTION public.messages_search_vector_update();
    END IF;
END $$;

-- Backfill search_vector for existing messages
UPDATE public.messages
SET search_vector = to_tsvector('simple', coalesce(content, ''))
WHERE search_vector IS NULL;

-- Indexes for cursor pagination, pinned lookup, and full-text search
CREATE INDEX IF NOT EXISTS idx_messages_room_cursor
    ON public.messages USING btree (room_id, id DESC);

CREATE INDEX IF NOT EXISTS idx_messages_room_pinned
    ON public.messages USING btree (room_id)
    WHERE pinned = true AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_messages_search_vector
    ON public.messages USING gin (search_vector);

-- -----------------------------------------------------------------------------
-- 5. Extend public.attachments for Neon bytea Storage (<= 5 MB)
-- -----------------------------------------------------------------------------
ALTER TABLE public.attachments
    ADD COLUMN IF NOT EXISTS data bytea;

-- Relax attachments_check so pre-uploaded files (PUT /api/files) can exist
-- before being bound to a room_id, direct_conversation_id, or message_id,
-- while still forbidding setting both room_id and direct_conversation_id at once.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'attachments_check'
          AND conrelid = 'public.attachments'::regclass
    ) THEN
        ALTER TABLE public.attachments DROP CONSTRAINT attachments_check;
    END IF;

    ALTER TABLE public.attachments
        ADD CONSTRAINT attachments_check
        CHECK (((((room_id IS NOT NULL))::integer + ((direct_conversation_id IS NOT NULL))::integer) <= 1));

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'attachments_max_size_check'
          AND conrelid = 'public.attachments'::regclass
    ) THEN
        ALTER TABLE public.attachments
            ADD CONSTRAINT attachments_max_size_check
            CHECK (size_bytes <= 5242880 AND (data IS NULL OR octet_length(data) <= 5242880));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_attachments_message_id
    ON public.attachments USING btree (message_id)
    WHERE message_id IS NOT NULL;

COMMIT;
