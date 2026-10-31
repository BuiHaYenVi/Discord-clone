import React, { useEffect, useRef } from 'react';

interface ConfirmModalProps {
  isOpen: boolean;
  title: string;
  message: string;
  confirmText?: string;
  cancelText?: string;
  isDanger?: boolean;
  isLoading?: boolean;
  errorMessage?: string;
  onConfirm: () => void;
  onCancel: () => void;
}

export const ConfirmModal: React.FC<ConfirmModalProps> = ({
  isOpen,
  title,
  message,
  confirmText = 'Xác nhận',
  cancelText = 'Hủy bỏ',
  isDanger = true,
  isLoading = false,
  errorMessage,
  onConfirm,
  onCancel
}) => {
  const dialogRef = useRef<HTMLDivElement>(null);
  const confirmBtnRef = useRef<HTMLButtonElement>(null);
  const cancelBtnRef = useRef<HTMLButtonElement>(null);
  const triggerElementRef = useRef<HTMLElement | null>(null);
  const isSubmittingRef = useRef<boolean>(false);

  // Reset cờ submitting khi trạng thái modal hoặc loading thay đổi
  useEffect(() => {
    if (!isLoading) {
      isSubmittingRef.current = false;
    }
  }, [isLoading, isOpen]);

  // Quản lý Focus Trap và trả Focus về phần tử kích hoạt trước đó
  useEffect(() => {
    if (!isOpen) return;

    // 1. Lưu lại phần tử đang được focus trước khi modal mở
    triggerElementRef.current = document.activeElement as HTMLElement | null;

    // 2. Tự động focus vào nút xác nhận khi modal xuất hiện
    const timer = setTimeout(() => {
      confirmBtnRef.current?.focus();
    }, 30);

    // 3. Xử lý bàn phím: Focus Trap (Tab/Shift+Tab) & Phím Escape
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        // Không cho phép bấm Esc khi đang trong quá trình gửi yêu cầu
        if (!isLoading) {
          onCancel();
        }
        return;
      }

      // Xử lý Focus Trap cho Tab và Shift+Tab
      if (e.key === 'Tab' && dialogRef.current) {
        const focusables = dialogRef.current.querySelectorAll<HTMLElement>(
          'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
        );

        if (focusables.length > 0) {
          const first = focusables[0];
          const last = focusables[focusables.length - 1];

          if (e.shiftKey) {
            // Shift + Tab: đi lùi
            if (document.activeElement === first) {
              e.preventDefault();
              last.focus();
            }
          } else {
            // Tab: đi tới
            if (document.activeElement === last) {
              e.preventDefault();
              first.focus();
            }
          }
        }
      }
      // LƯU Ý: KHÔNG bắt sự kiện 'Enter' toàn cục ở đây.
      // Mỗi button tự xử lý hành vi Enter tự nhiên của HTML:
      // Focus vào nút Hủy -> Enter sẽ Hủy. Focus vào nút Xác nhận -> Enter sẽ Xác nhận.
    };

    window.addEventListener('keydown', handleKeyDown);

    return () => {
      clearTimeout(timer);
      window.removeEventListener('keydown', handleKeyDown);

      // 4. Trả focus về phần tử kích hoạt ban đầu khi modal đóng
      if (triggerElementRef.current && typeof triggerElementRef.current.focus === 'function') {
        triggerElementRef.current.focus();
      }
    };
  }, [isOpen, isLoading, onCancel]);

  if (!isOpen) return null;

  const handleOverlayClick = () => {
    // Không cho phép đóng modal khi yêu cầu đang thực thi
    if (!isLoading) {
      onCancel();
    }
  };

  const handleConfirmClick = () => {
    // Chống gửi trùng lặp (Double Submit)
    if (isLoading || isSubmittingRef.current) {
      return;
    }
    isSubmittingRef.current = true;
    onConfirm();
  };

  return (
    <div
      className="modal-overlay"
      onClick={handleOverlayClick}
      style={{
        position: 'fixed',
        inset: 0,
        backgroundColor: 'rgba(0, 0, 0, 0.75)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 9999,
        padding: '16px'
      }}
    >
      <div
        ref={dialogRef}
        className="discord-modal"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby="confirm-modal-title"
      >
        <div className="discord-modal-body">
          <h3 id="confirm-modal-title" className="discord-modal-title">
            {title}
          </h3>
          <p className="discord-modal-message">
            {message}
          </p>
          {errorMessage && (
            <div className="discord-modal-error">
              ⚠️ {errorMessage}
            </div>
          )}
        </div>

        <div className="discord-modal-footer">
          <button
            ref={cancelBtnRef}
            type="button"
            className="discord-modal-btn-cancel"
            onClick={onCancel}
            disabled={isLoading}
          >
            {cancelText}
          </button>

          <button
            ref={confirmBtnRef}
            type="button"
            className={`discord-modal-btn-confirm ${isDanger ? 'danger' : 'primary'}`}
            onClick={handleConfirmClick}
            disabled={isLoading}
          >
            {isLoading ? 'Đang xử lý...' : confirmText}
          </button>
        </div>
      </div>
    </div>
  );
};
