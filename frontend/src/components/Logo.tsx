import React from 'react';

interface LogoProps {
  size?: number;
  className?: string;
  withGlow?: boolean;
}

export const Logo: React.FC<LogoProps> = ({ size = 32, className = '', withGlow = true }) => {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 48 48"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      className={className}
      style={{ display: 'inline-block', verticalAlign: 'middle', flexShrink: 0 }}
    >
      <defs>
        <linearGradient id="nexus-bg" x1="4" y1="4" x2="44" y2="44" gradientUnits="userSpaceOnUse">
          <stop stopColor="#4F46E5" />
          <stop offset="0.5" stopColor="#7C3AED" />
          <stop offset="1" stopColor="#06B6D4" />
        </linearGradient>
        <linearGradient id="nexus-bubble" x1="12" y1="12" x2="36" y2="34" gradientUnits="userSpaceOnUse">
          <stop stopColor="#FFFFFF" />
          <stop offset="1" stopColor="#E0E7FF" />
        </linearGradient>
        <filter id="nexus-glow" x="-15%" y="-15%" width="130%" height="130%">
          <feDropShadow dx="0" dy="3" stdDeviation="5" floodColor="#6366F1" floodOpacity="0.45" />
        </filter>
      </defs>

      {/* Khối nền Gradient công nghệ bo góc mềm mại */}
      <rect
        x="3"
        y="3"
        width="42"
        height="42"
        rx="13"
        fill="url(#nexus-bg)"
        filter={withGlow ? 'url(#nexus-glow)' : undefined}
      />

      {/* Bong bóng hội thoại trung tâm cách điệu */}
      <path
        d="M14 16.5C14 13.46 16.46 11 19.5 11H28.5C31.54 11 34 13.46 34 16.5V23.5C34 26.54 31.54 29 28.5 29H22.5L16.2 33.8C15.54 34.3 14 33.84 14 32.8V29C14 29 14 28 14 16.5Z"
        fill="url(#nexus-bubble)"
      />

      {/* Hai nút mạng TCP kết nối (Network Nodes) */}
      <circle cx="20" cy="20" r="2.3" fill="#4F46E5" />
      <circle cx="28" cy="20" r="2.3" fill="#7C3AED" />

      {/* Đường truyền kết nối dữ liệu mạng */}
      <path d="M22.3 20H25.7" stroke="#6366F1" strokeWidth="2" strokeLinecap="round" strokeDasharray="1.5 1.5" />

      {/* Sóng phát tín hiệu mạng Socket thời gian thực */}
      <path
        d="M23 15C24.5 15 25.8 15.6 26.8 16.6"
        stroke="#4F46E5"
        strokeWidth="1.8"
        strokeLinecap="round"
      />
    </svg>
  );
};

export default Logo;
