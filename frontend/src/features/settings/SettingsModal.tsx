import React, { useEffect, useState } from 'react';
import {
  Check,
  LogOut,
  User as UserIcon,
  X,
} from 'lucide-react';
import { getTranslation } from '../../shared/i18n/translations';
import { useAppStore } from '../../shared/state/appStore';
import type { PresenceStatus } from '../../shared/types/api';

const PRESENCE_OPTIONS: {
  status: PresenceStatus;
  labelVi: string;
  labelEn: string;
  color: string;
}[] = [
  { status: 'ONLINE', labelVi: 'Có mặt', labelEn: 'Available', color: 'var(--presence-online)' },
  { status: 'IDLE', labelVi: 'Tạm vắng', labelEn: 'Away', color: 'var(--presence-idle)' },
  { status: 'DND', labelVi: 'Bận', labelEn: 'Busy', color: 'var(--presence-dnd)' },
  { status: 'OFFLINE', labelVi: 'Ẩn trạng thái', labelEn: 'Invisible', color: 'var(--presence-offline)' },
];

export const SettingsModal: React.FC = () => {
  const {
    isSettingsOpen,
    setSettingsOpen,
    currentUser,
    updatePresence,
    logout,
    locale,
  } = useAppStore();

  const t = getTranslation(locale);
  const [statusOverride, setStatusOverride] = useState<{
    userId: number | undefined;
    status: PresenceStatus;
  } | null>(null);
  const [customStatusOverride, setCustomStatusOverride] = useState<{
    userId: number | undefined;
    text: string;
  } | null>(null);

  const selectedStatus: PresenceStatus =
    statusOverride && statusOverride.userId === currentUser?.id
      ? statusOverride.status
      : (currentUser?.status ?? 'ONLINE');

  const customStatusText: string =
    customStatusOverride && customStatusOverride.userId === currentUser?.id
      ? customStatusOverride.text
      : (currentUser?.customStatus ?? '');

  const setSelectedStatus = (status: PresenceStatus) => {
    setStatusOverride({ userId: currentUser?.id, status });
  };

  const setCustomStatusText = (text: string) => {
    setCustomStatusOverride({ userId: currentUser?.id, text });
  };

  useEffect(() => {
    if (!isSettingsOpen) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setSettingsOpen(false);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [isSettingsOpen, setSettingsOpen]);

  if (!isSettingsOpen) return null;

  const handleSavePresence = (e: React.FormEvent) => {
    e.preventDefault();
    updatePresence(selectedStatus, customStatusText.trim());
  };

  return (
    <div
      className="modal-backdrop"
      role="dialog"
      aria-modal="true"
      aria-label={t.settings.title}
      onClick={(e) => {
        if (e.target === e.currentTarget) setSettingsOpen(false);
      }}
    >
      <div className="modal-dialog settings-dialog">
        {/* Left Navigation Sidebar */}
        <aside className="settings-nav">
          <div
            className="text-label-sm"
            style={{
              padding: '4px 12px 10px',
              color: 'var(--on-surface-muted)',
            }}
          >
            {t.settings.title}
          </div>

          <button
            type="button"
            className="settings-nav-item active"
          >
            <UserIcon size={16} />
            <span>{t.settings.profileTab}</span>
          </button>

          <div style={{ flex: 1 }} />

          <button
            type="button"
            className="settings-nav-item"
            style={{ color: 'var(--error)' }}
            onClick={logout}
          >
            <LogOut size={16} />
            <span>{t.settings.logoutButton}</span>
          </button>
        </aside>

        {/* Right Content Area */}
        <section className="settings-content">
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              paddingBottom: '14px',
              borderBottom: '1px solid var(--border)',
            }}
          >
            <h2 className="text-headline-md">
              {t.settings.profileTab}
            </h2>
            <button
              type="button"
              className="btn btn-secondary btn-sm"
              onClick={() => setSettingsOpen(false)}
              aria-label={t.settings.closeButton}
            >
              <X size={15} />
              <span>{t.settings.closeButton}</span>
            </button>
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
            {/* Current User Summary Banner */}
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '16px',
                padding: '16px',
                borderRadius: 'var(--radius-md)',
                backgroundColor: 'var(--surface-subtle)',
                border: '1px solid var(--border)',
              }}
            >
              <div
                style={{
                  width: '52px',
                  height: '52px',
                  borderRadius: '9999px',
                  backgroundColor: currentUser?.avatarColor ?? '#2563EB',
                  color: '#FFFFFF',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  fontSize: '18px',
                  fontWeight: 700,
                  position: 'relative',
                }}
              >
                {(currentUser?.displayName ?? 'U')
                  .split(' ')
                  .slice(-2)
                  .map((w) => w[0])
                  .join('')
                  .toUpperCase()}
                <span
                  className={`presence-dot presence-${currentUser?.status ?? 'ONLINE'}`}
                  style={{
                    position: 'absolute',
                    right: '0px',
                    bottom: '0px',
                    width: '14px',
                    height: '14px',
                    border: '2px solid var(--surface)',
                  }}
                />
              </div>
              <div style={{ flex: 1 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <span className="text-headline-sm">{currentUser?.displayName}</span>
                  <span className="protocol-pill protocol-pill-tcp">
                    {currentUser?.role === 'OWNER' ? 'Chủ nhóm' : currentUser?.role === 'ADMIN' ? 'Quản trị viên' : 'Thành viên'}
                  </span>
                </div>
                <div className="text-mono-sm" style={{ color: 'var(--on-surface-secondary)' }}>
                  @{currentUser?.username}
                </div>
              </div>
            </div>

            {/* Presence & Custom Status Form */}
            <form
              onSubmit={handleSavePresence}
              style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}
            >
              <div className="field-group">
                <label className="field-label">{t.settings.statusLabel}</label>
                <div
                  style={{
                    display: 'grid',
                    gridTemplateColumns: 'repeat(2, 1fr)',
                    gap: '8px',
                  }}
                >
                  {PRESENCE_OPTIONS.map((opt) => {
                    const active = selectedStatus === opt.status;
                    return (
                      <button
                        key={opt.status}
                        type="button"
                        onClick={() => setSelectedStatus(opt.status)}
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: '10px',
                          padding: '10px 12px',
                          borderRadius: 'var(--radius-sm)',
                          border: `1px solid ${active ? 'var(--primary)' : 'var(--border)'}`,
                          backgroundColor: active ? 'var(--primary-soft)' : 'var(--surface-subtle)',
                          fontWeight: 600,
                          fontSize: '13px',
                          textAlign: 'left',
                        }}
                      >
                        <span
                          className="presence-dot"
                          style={{ backgroundColor: opt.color }}
                        />
                        <span style={{ flex: 1 }}>
                          {locale === 'vi' ? opt.labelVi : opt.labelEn}
                        </span>
                        {active && <Check size={15} style={{ color: 'var(--primary)' }} />}
                      </button>
                    );
                  })}
                </div>
              </div>

              <div className="field-group">
                <label htmlFor="settings-custom-status" className="field-label">
                  {t.settings.customStatusLabel}
                </label>
                <input
                  id="settings-custom-status"
                  type="text"
                  className="field-input"
                  value={customStatusText}
                  onChange={(e) => setCustomStatusText(e.target.value)}
                  placeholder={t.settings.customStatusPlaceholder}
                />
              </div>

              <div>
                <button type="submit" className="btn btn-primary">
                  <Check size={16} />
                  <span>{t.settings.saveStatus}</span>
                </button>
              </div>
            </form>
          </div>
        </section>
      </div>
    </div>
  );
};

export default SettingsModal;
