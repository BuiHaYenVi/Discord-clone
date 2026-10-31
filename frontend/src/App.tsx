import React from 'react';
import {
  AlertCircle,
  CheckCircle2,
  Info,
  Menu,
  Search,
  Users,
  X,
} from 'lucide-react';
import { AppStoreProvider, useAppStore } from './shared/state/appStore';
import { AuthScreen } from './features/auth/AuthScreen';
import { GuildRail } from './features/guild/GuildRail';
import { ChannelSidebar } from './features/guild/ChannelSidebar';
import { MemberSidebar } from './features/guild/MemberSidebar';
import { ChatStage } from './features/chat/ChatStage';
import { FriendsAndDmStage } from './features/friends/FriendsAndDmStage';
import { VoiceCallStage } from './features/voice/VoiceCallStage';
import { SettingsModal } from './features/settings/SettingsModal';
import { CommandPalette } from './features/settings/CommandPalette';
import './App.css';

const WorkspaceContent: React.FC = () => {
  const {
    isAuthenticated,
    viewMode,
    activeGuild,
    activeChannel,
    activeVoiceChannelId,
    isMemberSidebarOpen,
    setCommandPaletteOpen,
    mobileDrawerOpen,
    setMobileDrawerOpen,
    toasts,
    dismissToast,
  } = useAppStore();

  if (!isAuthenticated) {
    return (
      <>
        <AuthScreen />
        <ToastOverlay toasts={toasts} onDismiss={dismissToast} />
      </>
    );
  }

  return (
    <div className="workspace-viewport app-canvas-bg">
      <div className="workspace-layout-container" role="application" aria-label="Không gian làm việc Uri">
        {/* Left Floating Dock (~60px) */}
        <GuildRail />

        {/* Mobile backdrop for slide-over drawers */}
        {mobileDrawerOpen !== 'none' && (
          <div
            onClick={() => setMobileDrawerOpen('none')}
            style={{
              position: 'fixed',
              inset: 0,
              backgroundColor: 'rgba(15, 23, 42, 0.45)',
              zIndex: 55,
            }}
            aria-hidden="true"
          />
        )}

        {/* Card 1 Column: Independent Floating Search Pill above + Card 1 below */}
        <div
          className={`flex flex-col h-full shrink-0 w-[330px] xl:w-[350px] gap-3 transition-transform ${
            mobileDrawerOpen === 'channels' ? 'drawer-open' : ''
          }`}
        >
          {/* 1. Independent Floating Search Pill */}
          <div
            onClick={() => setCommandPaletteOpen(true)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                setCommandPaletteOpen(true);
              }
            }}
            className="floating-search-pill group"
            role="button"
            tabIndex={0}
            aria-label="Tìm kiếm nhanh trên Uri (Ctrl+K)"
          >
            <Search className="h-4 w-4 text-slate-400 group-hover:text-blue-600 dark:group-hover:text-blue-400 transition-colors shrink-0 mr-2.5" />
            <span className="text-[13.5px] font-semibold text-slate-500 dark:text-slate-400 select-none flex-1 truncate">
              Tìm kiếm trên Uri...
            </span>
            <span className="text-[11px] font-mono font-semibold px-2 py-0.5 rounded-full bg-blue-50 text-blue-600 border border-blue-200/80 dark:bg-blue-950/40 dark:text-blue-400 dark:border-blue-800/50">
              Ctrl+K
            </span>
          </div>

          {/* 2. Card 1 Shell: Messages / Channels */}
          <aside className="card-sidebar-shell flex-1">
            <ChannelSidebar />
          </aside>
        </div>

        {/* Card 2: Main Chat / Friends / Voice Stage Card (1fr) */}
        <main className="card-chat-shell">
          {/* Responsive mobile topbar (< 1024px) */}
          <div className="mobile-topbar">
            <button
              type="button"
              className="btn-ghost"
              onClick={() =>
                setMobileDrawerOpen(
                  mobileDrawerOpen === 'channels' ? 'none' : 'channels',
                )
              }
              aria-label="Mở danh sách kênh"
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: '6px',
                padding: '6px 10px',
                borderRadius: '8px',
              }}
            >
              <Menu size={16} />
              <span style={{ fontWeight: 700, fontSize: '13px' }}>
                {viewMode === 'guild'
                  ? `#${activeChannel?.name || activeGuild?.name || 'Chọn kênh'}`
                  : 'Bạn bè & DM'}
              </span>
            </button>

            <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
              <button
                type="button"
                className="btn-ghost"
                onClick={() => setCommandPaletteOpen(true)}
                aria-label="Tìm kiếm nhanh Ctrl+K"
                style={{ padding: '6px', borderRadius: '8px' }}
              >
                <Search size={16} />
              </button>
              {viewMode === 'guild' && (
                <button
                  type="button"
                  className="btn-ghost"
                  onClick={() =>
                    setMobileDrawerOpen(
                      mobileDrawerOpen === 'members' ? 'none' : 'members',
                    )
                  }
                  aria-label="Mở danh sách thành viên"
                  style={{ padding: '6px', borderRadius: '8px' }}
                >
                  <Users size={16} />
                </button>
              )}
            </div>
          </div>

          {/* WebRTC Voice/Video Call Stage (when connected to a voice channel) */}
          {activeVoiceChannelId !== null && <VoiceCallStage />}

          {/* Main Content: Guild Channel Chat OR Friends & 1-on-1 DM Stage */}
          <div
            style={{
              flex: '1 1 0%',
              minHeight: 0,
              display: 'flex',
              flexDirection: 'column',
              overflow: 'hidden',
            }}
          >
            {viewMode === 'guild' ? <ChatStage /> : <FriendsAndDmStage />}
          </div>

          {/* Slide-over Member Drawer (slides in gracefully over Card 2) */}
          {viewMode === 'guild' && isMemberSidebarOpen && (
            <div className="member-slide-drawer">
              <MemberSidebar />
            </div>
          )}
        </main>
      </div>

      {/* Global Modals & Overlays */}
      <SettingsModal />
      <CommandPalette />
      <ToastOverlay toasts={toasts} onDismiss={dismissToast} />
    </div>
  );
};

interface ToastOverlayProps {
  toasts: {
    id: string;
    type: 'info' | 'success' | 'warning' | 'error';
    title: string;
    message?: string;
  }[];
  onDismiss: (id: string) => void;
}

const ToastOverlay: React.FC<ToastOverlayProps> = ({ toasts, onDismiss }) => {
  if (!toasts.length) return null;

  const getBorderColor = (type: string) => {
    switch (type) {
      case 'success':
        return '#2563EB';
      case 'warning':
        return '#F59E0B';
      case 'error':
        return '#EF4444';
      default:
        return '#2563EB';
    }
  };

  const getIcon = (type: string) => {
    switch (type) {
      case 'success':
        return <CheckCircle2 size={16} style={{ color: '#2563EB', flexShrink: 0 }} />;
      case 'warning':
      case 'error':
        return (
          <AlertCircle
            size={16}
            style={{ color: type === 'error' ? '#EF4444' : '#F59E0B', flexShrink: 0 }}
          />
        );
      default:
        return <Info size={16} style={{ color: '#2563EB', flexShrink: 0 }} />;
    }
  };

  return (
    <div className="toast-stack" role="status" aria-live="polite">
      {toasts.map((t) => (
        <div
          key={t.id}
          className="toast-card"
          style={{ borderLeft: `4px solid ${getBorderColor(t.type)}` }}
        >
          <div style={{ display: 'flex', alignItems: 'flex-start', gap: '8px' }}>
            {getIcon(t.type)}
            <div>
              <div style={{ fontWeight: 700, fontSize: '13px' }}>{t.title}</div>
              {t.message && (
                <div
                  style={{
                    fontSize: '12px',
                    color: 'var(--text-secondary)',
                    marginTop: '2px',
                  }}
                >
                  {t.message}
                </div>
              )}
            </div>
          </div>
          <button
            type="button"
            onClick={() => onDismiss(t.id)}
            aria-label="Đóng thông báo"
            style={{
              background: 'transparent',
              border: 'none',
              cursor: 'pointer',
              color: 'var(--text-muted)',
              padding: '2px',
            }}
          >
            <X size={14} />
          </button>
        </div>
      ))}
    </div>
  );
};

export const App: React.FC = () => {
  return (
    <AppStoreProvider>
      <WorkspaceContent />
    </AppStoreProvider>
  );
};

export default App;
