import { useState } from 'react';
import { getTeamsEmojiAsset } from './TeamsEmojiCatalog';

interface TeamsEmojiProps {
  emoji: string;
  size?: number;
  alt?: string;
  className?: string;
}

export function TeamsEmoji({ emoji, size = 24, alt, className }: TeamsEmojiProps) {
  const [hasFailed, setHasFailed] = useState(false);
  const asset = getTeamsEmojiAsset(emoji);
  if (!asset || hasFailed) return <span className={className} style={{ fontSize: size, lineHeight: 1 }}>{emoji}</span>;
  return <img src={asset} alt={alt || emoji} width={size} height={size} draggable={false} className={'object-contain ' + (className || '')} onError={() => setHasFailed(true)} />;
}
