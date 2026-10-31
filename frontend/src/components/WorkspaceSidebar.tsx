import React from 'react';
import { Plus } from 'lucide-react';
import { Logo } from './Logo';

interface Props {
  onDirectMessage?: () => void;
  onOpenExplore?: () => void;
  onAddServer: () => void;
}

export const WorkspaceSidebar: React.FC<Props> = ({ onAddServer }) => {
  return (
    <div className="workspace-strip">
      <div className="workspace-pill-wrap active">
        <span className="pill-marker" />
        <button
          className="pill-item active"
          title="LTMang Chat - Danh sách phòng"
        >
          <Logo size={30} withGlow={false} />
        </button>
      </div>

      <div className="strip-separator" />

      <div className="workspace-pill-wrap">
        <span className="pill-marker" />
        <button
          className="pill-item green"
          title="Tạo phòng chat mới"
          onClick={onAddServer}
        >
          <Plus size={20} />
        </button>
      </div>
    </div>
  );
};
