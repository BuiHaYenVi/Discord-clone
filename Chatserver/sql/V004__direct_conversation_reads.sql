-- Per-user direct conversation read cursors and unread-count support.
-- Idempotent: safe to execute more than once.
BEGIN;

CREATE TABLE IF NOT EXISTS public.direct_conversation_reads (
    conversation_id bigint NOT NULL REFERENCES public.direct_conversations(id) ON DELETE CASCADE,
    user_id integer NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    last_read_message_id bigint REFERENCES public.direct_messages(id) ON DELETE SET NULL,
    last_read_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY (conversation_id, user_id),
    CONSTRAINT direct_conversation_reads_message_id_check
        CHECK (last_read_message_id IS NULL OR last_read_message_id > 0)
);

CREATE INDEX IF NOT EXISTS idx_direct_messages_unread_lookup
    ON public.direct_messages (conversation_id, sender_id, id)
    WHERE deleted_at IS NULL;

COMMIT;
