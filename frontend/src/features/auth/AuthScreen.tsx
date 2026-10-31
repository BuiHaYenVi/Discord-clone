import React, { useState } from 'react';
import { motion } from 'framer-motion';
import {
  ArrowRight,
  Globe,
  Image,
  Lock,
  MessageSquareHeart,
  Mic,
  Moon,
  Sparkles,
  Sun,
  UserCheck,
  UserPlus,
  Users,
} from 'lucide-react';
import { getTranslation } from '../../shared/i18n/translations';
import { useAppStore } from '../../shared/state/appStore';
import { userFacingError } from '../../shared/ui/userFacingError';

export const AuthScreen: React.FC = () => {
  const {
    login,
    register,
    theme,
    toggleTheme,
    locale,
    setLocale,
  } = useAppStore();

  const t = getTranslation(locale);
  const isVi = locale === 'vi';

  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const features = isVi
    ? [
        {
          icon: MessageSquareHeart,
          title: 'Trò chuyện cá nhân & Kênh nhóm',
          desc: 'Nhắn tin trực tiếp với bạn bè hoặc tham gia các kênh thảo luận theo từng chủ đề yêu thích.',
          accent: '#2563eb',
          bg: 'rgba(37, 99, 235, 0.1)',
        },
        {
          icon: Image,
          title: 'Chia sẻ Hình ảnh & Tài liệu',
          desc: 'Gửi hình ảnh, tệp đính kèm và xem trước nội dung ngay trong cuộc trò chuyện chỉ với một thao tác.',
          accent: '#0284c7',
          bg: 'rgba(2, 132, 199, 0.1)',
        },
        {
          icon: Mic,
          title: 'Tin nhắn thoại Tức thì',
          desc: 'Ghi âm và gửi lời nhắn thoại nhanh chóng cùng biểu đồ sóng âm trực quan, sinh động.',
          accent: '#0d9488',
          bg: 'rgba(13, 148, 136, 0.1)',
        },
        {
          icon: Users,
          title: 'Kết nối Bạn bè Mọi lúc',
          desc: 'Thả biểu tượng cảm xúc, ghim tin nhắn quan trọng và xem trạng thái hoạt động của bạn bè.',
          accent: '#4f46e5',
          bg: 'rgba(79, 70, 229, 0.1)',
        },
      ]
    : [
        {
          icon: MessageSquareHeart,
          title: 'Direct & Channel Messaging',
          desc: 'Chat one-on-one with friends or collaborate in organized topic channels with your team.',
          accent: '#2563eb',
          bg: 'rgba(37, 99, 235, 0.1)',
        },
        {
          icon: Image,
          title: 'Effortless Media & File Sharing',
          desc: 'Share photos, documents, and preview attachments right inside your conversation stream.',
          accent: '#0284c7',
          bg: 'rgba(2, 132, 199, 0.1)',
        },
        {
          icon: Mic,
          title: 'Expressive Voice Notes',
          desc: 'Record and send crystal-clear voice messages with interactive waveform playback.',
          accent: '#0d9488',
          bg: 'rgba(13, 148, 136, 0.1)',
        },
        {
          icon: Users,
          title: 'Stay Connected with Friends',
          desc: 'React with emojis, pin important messages, and see who is online in real time.',
          accent: '#4f46e5',
          bg: 'rgba(79, 70, 229, 0.1)',
        },
      ];

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMsg(null);

    if (!username.trim()) {
      setErrorMsg(isVi ? 'Vui lòng nhập tên đăng nhập.' : 'Username is required.');
      return;
    }
    if (!password.trim()) {
      setErrorMsg(isVi ? 'Vui lòng nhập mật khẩu.' : 'Password is required.');
      return;
    }

    setSubmitting(true);
    try {
      if (mode === 'login') {
        await login(username.trim(), password);
      } else {
        await register(username.trim(), password, displayName.trim() || username.trim());
      }
    } catch (err) {
      setErrorMsg(userFacingError(err, {
        locale,
        action: mode === 'login' ? 'Đăng nhập' : 'Tạo tài khoản',
        fallback: mode === 'login'
          ? 'Không thể đăng nhập lúc này. Vui lòng thử lại.'
          : 'Không thể tạo tài khoản lúc này. Vui lòng thử lại.',
        fallbackEn: mode === 'login'
          ? 'You could not sign in. Please try again.'
          : 'Your account could not be created. Please try again.',
      }));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="auth-viewport">
      {/* Top Header Bar */}
      <header className="auth-topbar">
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <img
            src="/uri-logo.png"
            alt="Uri"
            style={{ width: '44px', height: '44px', objectFit: 'contain' }}
            draggable={false}
          />
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <span className="text-headline-md">{t.appName}</span>
            </div>
            <p className="text-body-sm" style={{ color: 'var(--on-surface-secondary)' }}>
              {t.appSubtitle}
            </p>
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <button
            type="button"
            className="btn btn-secondary btn-sm"
            onClick={() => setLocale(isVi ? 'en' : 'vi')}
            aria-label={isVi ? 'Đổi ngôn ngữ' : 'Change language'}
          >
            <Globe size={15} />
            <span>{isVi ? 'Tiếng Việt' : 'English'}</span>
          </button>

          <button
            type="button"
            className="btn btn-secondary btn-sm"
            onClick={toggleTheme}
            aria-label={isVi ? 'Đổi giao diện sáng hoặc tối' : 'Change light or dark appearance'}
          >
            {theme === 'light' ? <Moon size={15} /> : <Sun size={15} />}
            <span>{theme === 'light' ? (isVi ? 'Giao diện Tối' : 'Dark Mode') : (isVi ? 'Giao diện Sáng' : 'Light Mode')}</span>
          </button>
        </div>
      </header>

      {/* Main Split Workspace */}
      <main className="auth-shell">
        {/* Left Column: User-Focused Product Showcase */}
        <section className="auth-hero-panel">
          <div
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: '8px',
              alignSelf: 'flex-start',
              padding: '6px 14px',
              borderRadius: '9999px',
              backgroundColor: 'var(--primary-soft)',
              color: 'var(--primary)',
              border: '1px solid var(--primary-tint)',
              fontSize: '12.5px',
              fontWeight: 700,
            }}
          >
            <Sparkles size={14} />
            <span>
              {isVi
                ? 'Không gian trò chuyện trực quan & hiện đại'
                : 'Intuitive & Modern Messaging Space'}
            </span>
          </div>

          <h1
            className="text-headline-display"
            style={{ fontSize: '38px', lineHeight: 1.18, letterSpacing: '-0.02em' }}
          >
            {t.auth.welcomeTitle}
          </h1>

          <p
            className="text-body-lg"
            style={{ color: 'var(--on-surface-secondary)', maxWidth: '58ch', lineHeight: 1.6 }}
          >
            {t.auth.welcomeSubtitle}
          </p>

          {/* 4 User-Friendly Highlights Grid */}
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
              gap: '14px',
            }}
          >
            {features.map((item) => {
              const Icon = item.icon;
              return (
                <div
                  key={item.title}
                  style={{
                    padding: '18px',
                    borderRadius: '18px',
                    backgroundColor: 'var(--surface)',
                    border: '1px solid var(--border)',
                    boxShadow: 'var(--shadow-xs)',
                    display: 'flex',
                    flexDirection: 'column',
                    gap: '10px',
                  }}
                >
                  <div
                    style={{
                      width: '38px',
                      height: '38px',
                      borderRadius: '12px',
                      backgroundColor: item.bg,
                      color: item.accent,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                    }}
                  >
                    <Icon size={19} strokeWidth={2} />
                  </div>
                  <div className="text-label-lg" style={{ fontSize: '15px' }}>
                    {item.title}
                  </div>
                  <p
                    className="text-body-sm"
                    style={{ color: 'var(--on-surface-secondary)', lineHeight: 1.5 }}
                  >
                    {item.desc}
                  </p>
                </div>
              );
            })}
          </div>

        </section>

        {/* Right Column: User Login / Register Card */}
        <section className="auth-card" aria-label={isVi ? 'Đăng nhập hoặc tạo tài khoản' : 'Sign in or create an account'}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
            <h2 className="text-headline-md" style={{ fontSize: '22px' }}>
              {mode === 'login'
                ? isVi
                  ? 'Chào mừng bạn trở lại!'
                  : 'Welcome back!'
                : isVi
                  ? 'Tạo tài khoản Uri mới'
                  : 'Create your Uri account'}
            </h2>
            <p className="text-body-sm" style={{ color: 'var(--on-surface-secondary)' }}>
              {mode === 'login'
                ? isVi
                  ? 'Đăng nhập để tiếp tục trò chuyện cùng bạn bè và đội nhóm.'
                  : 'Sign in to continue chatting with your friends and teams.'
                : isVi
                  ? 'Chỉ mất vài giây để bắt đầu kết nối trên Uri.'
                  : 'It only takes a few seconds to get started on Uri.'}
            </p>
          </div>

          {/* Mode Switcher Tabs with Framer Motion */}
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: '1fr 1fr',
              gap: '6px',
              padding: '4px',
              borderRadius: '14px',
              backgroundColor: 'var(--surface-muted)',
              border: '1px solid var(--border)',
            }}
          >
            <button
              type="button"
              className="btn btn-sm relative"
              style={{
                backgroundColor: 'transparent',
                color: mode === 'login' ? 'var(--primary)' : 'var(--on-surface-secondary)',
                position: 'relative',
                zIndex: 1,
              }}
              onClick={() => {
                setMode('login');
                setErrorMsg(null);
              }}
            >
              {mode === 'login' && (
                <motion.span
                  layoutId="auth-mode-pill"
                  transition={{ type: 'spring', stiffness: 400, damping: 30 }}
                  style={{
                    position: 'absolute',
                    inset: 0,
                    borderRadius: '10px',
                    backgroundColor: 'var(--surface)',
                    boxShadow: 'var(--shadow-sm)',
                    zIndex: -1,
                  }}
                />
              )}
              <UserCheck size={15} />
              <span>{t.auth.loginTab}</span>
            </button>
            <button
              type="button"
              className="btn btn-sm relative"
              style={{
                backgroundColor: 'transparent',
                color: mode === 'register' ? 'var(--primary)' : 'var(--on-surface-secondary)',
                position: 'relative',
                zIndex: 1,
              }}
              onClick={() => {
                setMode('register');
                setErrorMsg(null);
              }}
            >
              {mode === 'register' && (
                <motion.span
                  layoutId="auth-mode-pill"
                  transition={{ type: 'spring', stiffness: 400, damping: 30 }}
                  style={{
                    position: 'absolute',
                    inset: 0,
                    borderRadius: '10px',
                    backgroundColor: 'var(--surface)',
                    boxShadow: 'var(--shadow-sm)',
                    zIndex: -1,
                  }}
                />
              )}
              <UserPlus size={15} />
              <span>{t.auth.registerTab}</span>
            </button>
          </div>

          {/* Standard Login / Register Form */}
          <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
            <div className="field-group">
              <label htmlFor="auth-username" className="field-label">
                {t.auth.usernameLabel}
              </label>
              <input
                id="auth-username"
                type="text"
                className="field-input"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder={t.auth.usernamePlaceholder}
                autoComplete="username"
              />
            </div>

            {mode === 'register' && (
              <div className="field-group">
                <label htmlFor="auth-displayname" className="field-label">
                  {t.auth.displayNameLabel}
                </label>
                <input
                  id="auth-displayname"
                  type="text"
                  className="field-input"
                  value={displayName}
                  onChange={(e) => setDisplayName(e.target.value)}
                  placeholder={t.auth.displayNamePlaceholder}
                  autoComplete="name"
                />
              </div>
            )}

            <div className="field-group">
              <label htmlFor="auth-password" className="field-label">
                {t.auth.passwordLabel}
              </label>
              <input
                id="auth-password"
                type="password"
                className="field-input"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder={t.auth.passwordPlaceholder}
                autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
              />
            </div>

            {errorMsg && (
              <div
                className="field-error"
                style={{
                  padding: '8px 12px',
                  borderRadius: 'var(--radius-sm)',
                  backgroundColor: 'var(--error-soft)',
                  border: '1px solid var(--error)',
                }}
              >
                {errorMsg}
              </div>
            )}

            <button
              type="submit"
              className="btn btn-primary"
              disabled={submitting}
              style={{ width: '100%', marginTop: '4px' }}
            >
              <Lock size={16} />
              <span>
                {submitting
                  ? isVi
                    ? 'Đang xử lý...'
                    : 'Please wait...'
                  : mode === 'login'
                    ? t.auth.loginButton
                    : t.auth.registerButton}
              </span>
              <ArrowRight size={16} />
            </button>
          </form>

          {/* Divider */}
        </section>
      </main>
    </div>
  );
};

export default AuthScreen;
