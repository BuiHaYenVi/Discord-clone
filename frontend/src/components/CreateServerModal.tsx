import React, { useState } from 'react';
import { X, ChevronRight, ArrowLeft } from 'lucide-react';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  onCreateRoom: (name: string, description?: string) => void;
  onJoinRoom?: (roomIdOrCode: string) => void;
  currentUserName?: string;
}

type ModalStep = 'templates' | 'customize' | 'join';

interface TemplateOption {
  id: string;
  name: string;
  icon: string;
  defaultName: string;
  defaultDesc: string;
}

const TEMPLATES: TemplateOption[] = [
  {
    id: 'gaming',
    name: 'Gaming',
    icon: '🎮',
    defaultName: 'Gaming',
    defaultDesc: 'Nơi thảo luận, chia sẻ mẹo và lập đội leo rank cùng bạn bè.'
  },
  {
    id: 'friends',
    name: 'Bạn bè',
    icon: '💖',
    defaultName: 'Hội Bạn Thân',
    defaultDesc: 'Phòng chat thân mật để chia sẻ cuộc sống hàng ngày và tin tức.'
  },
  {
    id: 'study',
    name: 'Học Tập',
    icon: '📚',
    defaultName: 'Học Tập',
    defaultDesc: 'Không gian yên tĩnh để cùng nhau học tập, làm bài tập và chia sẻ tài liệu.'
  },
  {
    id: 'general',
    name: 'Thảo Luận Chung',
    icon: '💬',
    defaultName: 'Thảo Luận',
    defaultDesc: 'Kênh trò chuyện, trao đổi thông tin tự do cho mọi thành viên.'
  }
];

const AVATAR_OPTIONS = ['💬', '🎮', '📚', '🚀', '🌟', '🎵', '☕', '⚡'];

export const CreateServerModal: React.FC<Props> = ({
  isOpen,
  onClose,
  onCreateRoom,
  onJoinRoom,
  currentUserName = ''
}) => {
  const [step, setStep] = useState<ModalStep>('templates');
  const [serverName, setServerName] = useState('');
  const [serverDesc, setServerDesc] = useState('');
  const [selectedAvatar, setSelectedAvatar] = useState('💬');
  const [inviteInput, setInviteInput] = useState('');
  const [errorMsg, setErrorMsg] = useState('');

  if (!isOpen) return null;

  const handleSelectTemplate = (template?: TemplateOption) => {
    setErrorMsg('');
    const userSuffix = currentUserName ? ` - ${currentUserName}` : '';
    if (template) {
      setServerName(`${template.defaultName}${userSuffix}`);
      setServerDesc(template.defaultDesc);
      setSelectedAvatar(template.icon);
    } else {
      setServerName(`Phòng chat mới`);
      setServerDesc('');
      setSelectedAvatar('💬');
    }
    setStep('customize');
  };

  const handleSubmitCreate = (e: React.FormEvent) => {
    e.preventDefault();
    if (!serverName.trim()) {
      setErrorMsg('Vui lòng nhập tên phòng chat.');
      return;
    }
    const finalName = serverName.trim();
    onCreateRoom(finalName, serverDesc.trim());
    handleClose();
  };

  const handleSubmitJoin = (e: React.FormEvent) => {
    e.preventDefault();
    if (!inviteInput.trim()) {
      setErrorMsg('Vui lòng nhập mã mời hoặc ID phòng.');
      return;
    }
    if (onJoinRoom) {
      onJoinRoom(inviteInput.trim());
    }
    handleClose();
  };

  const handleClose = () => {
    setStep('templates');
    setServerName('');
    setServerDesc('');
    setInviteInput('');
    setErrorMsg('');
    onClose();
  };

  return (
    <div className="modal-backdrop" onClick={handleClose}>
      <div
        className="modal-card server-modal-card"
        onClick={e => e.stopPropagation()}
        style={{ maxWidth: '460px', width: '100%', position: 'relative' }}
      >
        {/* Nút Đóng (X) */}
        <button
          onClick={handleClose}
          style={{
            position: 'absolute',
            top: '16px',
            right: '16px',
            background: 'none',
            border: 'none',
            color: 'var(--text-muted)',
            cursor: 'pointer',
            padding: '4px',
            borderRadius: '50%',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            transition: 'color 0.15s'
          }}
          title="Đóng"
        >
          <X size={20} />
        </button>

        {/* ============================================================== */}
        {/* BƯỚC 1: DANH SÁCH MẪU MÁY CHỦ (Y HỆT DISCORD TRONG ẢNH)        */}
        {/* ============================================================== */}
        {step === 'templates' && (
          <div>
            <div style={{ textAlign: 'center', marginBottom: '20px', paddingRight: '20px', paddingLeft: '20px' }}>
              <h2 style={{ fontSize: '24px', fontWeight: 700, color: 'var(--text-header)', margin: '0 0 8px' }}>
                Tạo Máy Chủ Của Bạn
              </h2>
              <p style={{ fontSize: '14px', color: 'var(--text-muted)', lineHeight: '1.5', margin: 0 }}>
                Máy chủ của bạn là nơi bạn giao lưu với bạn bè của mình. Hãy tạo máy chủ của riêng bạn và bắt đầu trò chuyện.
              </p>
            </div>

            {/* Mục lớn: Tạo Mẫu Riêng */}
            <div
              className="server-template-item custom"
              onClick={() => handleSelectTemplate()}
              style={{
                display: 'flex',
                alignItems: 'center',
                padding: '12px 16px',
                background: 'var(--bg-secondary)',
                border: '1px solid var(--border-color)',
                borderRadius: '8px',
                cursor: 'pointer',
                marginBottom: '16px',
                transition: 'all 0.15s ease'
              }}
            >
              <div
                style={{
                  width: '40px',
                  height: '40px',
                  borderRadius: '50%',
                  background: 'rgba(99, 102, 241, 0.15)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  fontSize: '20px',
                  flexShrink: 0
                }}
              >
                🎨
              </div>
              <span style={{ flex: 1, marginLeft: '12px', fontSize: '15px', fontWeight: 600, color: 'var(--text-header)' }}>
                Tạo Mẫu Riêng
              </span>
              <ChevronRight size={18} color="var(--text-muted)" />
            </div>

            {/* Phân nhóm: Bắt đầu từ mẫu */}
            <div style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '8px', letterSpacing: '0.5px' }}>
              Bắt đầu từ mẫu
            </div>

            {/* 4 Mẫu có sẵn */}
            <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
              {TEMPLATES.map(tpl => (
                <div
                  key={tpl.id}
                  className="server-template-item"
                  onClick={() => handleSelectTemplate(tpl)}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    padding: '12px 16px',
                    background: 'var(--bg-secondary)',
                    border: '1px solid var(--border-color)',
                    borderRadius: '8px',
                    cursor: 'pointer',
                    transition: 'all 0.15s ease'
                  }}
                >
                  <div
                    style={{
                      width: '38px',
                      height: '38px',
                      borderRadius: '8px',
                      background: 'var(--bg-primary)',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      fontSize: '18px',
                      flexShrink: 0
                    }}
                  >
                    {tpl.icon}
                  </div>
                  <span style={{ flex: 1, marginLeft: '12px', fontSize: '15px', fontWeight: 600, color: 'var(--text-header)' }}>
                    {tpl.name}
                  </span>
                  <ChevronRight size={18} color="var(--text-muted)" />
                </div>
              ))}
            </div>

            {/* Footer: Bạn đã nhận được lời mời rồi? */}
            <div
              style={{
                marginTop: '20px',
                paddingTop: '16px',
                borderTop: '1px solid var(--border-color)',
                textAlign: 'center'
              }}
            >
              <div style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-header)', marginBottom: '10px' }}>
                Bạn đã nhận được lời mời rồi?
              </div>
              <button
                type="button"
                className="btn-join-server"
                onClick={() => { setErrorMsg(''); setStep('join'); }}
                style={{
                  width: '100%',
                  padding: '12px',
                  background: 'var(--bg-hover)',
                  color: 'var(--text-header)',
                  border: 'none',
                  borderRadius: '6px',
                  fontWeight: 600,
                  fontSize: '14px',
                  cursor: 'pointer',
                  transition: 'background 0.15s'
                }}
              >
                Tham gia máy chủ
              </button>
            </div>
          </div>
        )}

        {/* ============================================================== */}
        {/* BƯỚC 2A: TÙY CHỈNH MÁY CHỦ (TÊN, AVATAR, MÔ TẢ)               */}
        {/* ============================================================== */}
        {step === 'customize' && (
          <form onSubmit={handleSubmitCreate}>
            <div style={{ textAlign: 'center', marginBottom: '20px', paddingRight: '20px', paddingLeft: '20px' }}>
              <h2 style={{ fontSize: '24px', fontWeight: 700, color: 'var(--text-header)', margin: '0 0 8px' }}>
                Tùy chỉnh máy chủ của bạn
              </h2>
              <p style={{ fontSize: '14px', color: 'var(--text-muted)', lineHeight: '1.5', margin: 0 }}>
                Hãy đặt tên và biểu tượng cho máy chủ mới để tạo dấu ấn riêng. Bạn có thể thay đổi bất kỳ lúc nào.
              </p>
            </div>

            {/* Chọn Avatar biểu tượng */}
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', marginBottom: '20px' }}>
              <div
                style={{
                  width: '76px',
                  height: '76px',
                  borderRadius: '50%',
                  background: 'var(--bg-secondary)',
                  border: '2px dashed var(--accent-blurple)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  fontSize: '36px',
                  cursor: 'pointer',
                  boxShadow: '0 4px 12px rgba(0,0,0,0.2)'
                }}
                title="Biểu tượng đại diện máy chủ"
              >
                {selectedAvatar}
              </div>
              <span style={{ fontSize: '11px', color: 'var(--text-muted)', marginTop: '6px' }}>
                Chọn biểu tượng bên dưới:
              </span>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px', justifyContent: 'center', marginTop: '8px', maxWidth: '320px' }}>
                {AVATAR_OPTIONS.map(emoji => (
                  <button
                    key={emoji}
                    type="button"
                    onClick={() => setSelectedAvatar(emoji)}
                    style={{
                      width: '32px',
                      height: '32px',
                      borderRadius: '6px',
                      background: selectedAvatar === emoji ? 'var(--accent-blurple)' : 'var(--bg-secondary)',
                      border: 'none',
                      fontSize: '16px',
                      cursor: 'pointer',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      transition: 'all 0.15s'
                    }}
                  >
                    {emoji}
                  </button>
                ))}
              </div>
            </div>

            {errorMsg && (
              <div style={{ color: 'var(--accent-red)', fontSize: '13px', marginBottom: '12px', textAlign: 'center' }}>
                ⚠️ {errorMsg}
              </div>
            )}

            {/* Ô Tên Máy Chủ */}
            <div className="modal-field" style={{ marginBottom: '14px' }}>
              <label style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '6px', display: 'block' }}>
                TÊN MÁY CHỦ <span style={{ color: 'var(--accent-red)' }}>*</span>
              </label>
              <input
                type="text"
                value={serverName}
                onChange={e => setServerName(e.target.value)}
                placeholder="Nhập tên máy chủ của bạn..."
                autoFocus
                maxLength={80}
                style={{ width: '100%', boxSizing: 'border-box' }}
              />
            </div>

            {/* Ô Mô Tả */}
            <div className="modal-field" style={{ marginBottom: '20px' }}>
              <label style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '6px', display: 'block' }}>
                MÔ TẢ MÁY CHỦ (TÙY CHỌN)
              </label>
              <textarea
                value={serverDesc}
                onChange={e => setServerDesc(e.target.value)}
                placeholder="Mô tả mục đích và quy định của máy chủ..."
                rows={2}
                maxLength={200}
                style={{ width: '100%', boxSizing: 'border-box', resize: 'none' }}
              />
            </div>

            {/* Footer Buttons */}
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', paddingTop: '14px', borderTop: '1px solid var(--border-color)' }}>
              <button
                type="button"
                onClick={() => setStep('templates')}
                style={{
                  background: 'none',
                  border: 'none',
                  color: 'var(--text-muted)',
                  cursor: 'pointer',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '4px',
                  fontSize: '14px',
                  fontWeight: 600,
                  padding: '8px 12px'
                }}
              >
                <ArrowLeft size={16} /> Quay lại
              </button>

              <button
                type="submit"
                className="btn-primary"
                style={{
                  background: 'var(--accent-blurple)',
                  color: '#fff',
                  border: 'none',
                  borderRadius: '4px',
                  padding: '10px 24px',
                  fontWeight: 600,
                  fontSize: '14px',
                  cursor: 'pointer'
                }}
              >
                Tạo
              </button>
            </div>
          </form>
        )}

        {/* ============================================================== */}
        {/* BƯỚC 2B: THAM GIA MÁY CHỦ BẰNG MÃ MỜI HOẶC ID PHÒNG           */}
        {/* ============================================================== */}
        {step === 'join' && (
          <form onSubmit={handleSubmitJoin}>
            <div style={{ textAlign: 'center', marginBottom: '20px', paddingRight: '20px', paddingLeft: '20px' }}>
              <h2 style={{ fontSize: '24px', fontWeight: 700, color: 'var(--text-header)', margin: '0 0 8px' }}>
                Tham gia một máy chủ
              </h2>
              <p style={{ fontSize: '14px', color: 'var(--text-muted)', lineHeight: '1.5', margin: 0 }}>
                Nhập liên kết mời hoặc mã mời để tham gia máy chủ của bạn bè.
              </p>
            </div>

            {errorMsg && (
              <div style={{ color: 'var(--accent-red)', fontSize: '13px', marginBottom: '12px', textAlign: 'center' }}>
                ⚠️ {errorMsg}
              </div>
            )}

            <div className="modal-field" style={{ marginBottom: '14px' }}>
              <label style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '6px', display: 'block' }}>
                LIÊN KẾT HOẶC MÃ MỜI <span style={{ color: 'var(--accent-red)' }}>*</span>
              </label>
              <input
                type="text"
                value={inviteInput}
                onChange={e => setInviteInput(e.target.value)}
                placeholder="Ví dụ: h8k2p9a1 hoặc ID phòng (ví dụ: 1)"
                autoFocus
                style={{ width: '100%', boxSizing: 'border-box' }}
              />
            </div>

            <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '20px', background: 'var(--bg-secondary)', padding: '10px 12px', borderRadius: '6px' }}>
              💡 <strong>Mẹo:</strong> Lời mời có thể là mã số phòng (như <code>1</code>, <code>2</code>) hoặc mã mời ngẫu nhiên do chủ phòng cung cấp.
            </div>

            {/* Footer Buttons */}
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', paddingTop: '14px', borderTop: '1px solid var(--border-color)' }}>
              <button
                type="button"
                onClick={() => setStep('templates')}
                style={{
                  background: 'none',
                  border: 'none',
                  color: 'var(--text-muted)',
                  cursor: 'pointer',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '4px',
                  fontSize: '14px',
                  fontWeight: 600,
                  padding: '8px 12px'
                }}
              >
                <ArrowLeft size={16} /> Quay lại
              </button>

              <button
                type="submit"
                className="btn-primary"
                style={{
                  background: 'var(--accent-blurple)',
                  color: '#fff',
                  border: 'none',
                  borderRadius: '4px',
                  padding: '10px 24px',
                  fontWeight: 600,
                  fontSize: '14px',
                  cursor: 'pointer'
                }}
              >
                Tham gia máy chủ
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
};

export default CreateServerModal;
