export type Locale = 'vi' | 'en';

export interface TranslationDictionary {
  appName: string;
  appSubtitle: string;
  auth: {
    welcomeTitle: string;
    welcomeSubtitle: string;
    loginTab: string;
    registerTab: string;
    usernameLabel: string;
    usernamePlaceholder: string;
    passwordLabel: string;
    passwordPlaceholder: string;
    displayNameLabel: string;
    displayNamePlaceholder: string;
    loginButton: string;
    registerButton: string;
    backendStatusLive: string;
  };
  workspace: {
    directMessages: string;
    friends: string;
    textChannels: string;
    voiceChannels: string;
    onlineMembers: string;
    offlineMembers: string;
    searchPlaceholder: string;
    pinnedMessages: string;
    protocolMonitor: string;
    commandPaletteHint: string;
    messagePlaceholder: string;
    replyingTo: string;
    editingMessage: string;
    typingSuffix: string;
  };
  settings: {
    title: string;
    profileTab: string;
    appearanceTab: string;
    networkTab: string;
    shortcutsTab: string;
    themeLabel: string;
    themeLight: string;
    themeDark: string;
    languageLabel: string;
    statusLabel: string;
    customStatusLabel: string;
    customStatusPlaceholder: string;
    saveStatus: string;
    udpSimulationLabel: string;
    connectionModeLabel: string;
    logoutButton: string;
    closeButton: string;
  };
  commandPalette: {
    placeholder: string;
    emptyState: string;
    groupNavigation: string;
    groupActions: string;
    groupStatus: string;
    toggleTheme: string;
    toggleMonitor: string;
    openSettings: string;
    openFriends: string;
    probeRtt: string;
  };
}

export const TRANSLATIONS: Record<Locale, TranslationDictionary> = {
  vi: {
    appName: 'Uri',
    appSubtitle: 'Không gian trò chuyện & kết nối đội nhóm hiện đại',
    auth: {
      welcomeTitle: 'Kết nối và trò chuyện mọi lúc cùng Uri',
      welcomeSubtitle:
        'Nhắn tin cá nhân, thảo luận theo kênh cộng đồng, chia sẻ hình ảnh, tài liệu và gửi tin nhắn thoại mượt mà trong một không gian duy nhất.',
      loginTab: 'Đăng nhập',
      registerTab: 'Đăng ký tài khoản',
      usernameLabel: 'Tên đăng nhập',
      usernamePlaceholder: 'Nhập tên đăng nhập của bạn',
      passwordLabel: 'Mật khẩu',
      passwordPlaceholder: 'Nhập mật khẩu của bạn',
      displayNameLabel: 'Tên hiển thị',
      displayNamePlaceholder: 'Ví dụ: Bùi Hà Yến Vi',
      loginButton: 'Đăng nhập vào Uri',
      registerButton: 'Tạo tài khoản & Bắt đầu',
      backendStatusLive: 'Trực tuyến',
    },
    workspace: {
      directMessages: 'Tin nhắn trực tiếp',
      friends: 'Bạn bè',
      textChannels: 'KÊNH TRÒ CHUYỆN',
      voiceChannels: 'KÊNH THOẠI & VIDEO',
      onlineMembers: 'TRỰC TUYẾN',
      offlineMembers: 'NGOẠI TUYẾN',
      searchPlaceholder: 'Tìm kiếm tin nhắn...',
      pinnedMessages: 'Tin nhắn đã ghim',
      protocolMonitor: 'Tình trạng kết nối',
      commandPaletteHint: 'Tìm nhanh (Ctrl+K)',
      messagePlaceholder: 'Nhắn tin tới kênh',
      replyingTo: 'Đang trả lời',
      editingMessage: 'Đang chỉnh sửa tin nhắn',
      typingSuffix: 'đang soạn tin',
    },
    settings: {
      title: 'Cài đặt Uri & Hồ sơ',
      profileTab: 'Hồ sơ & Trạng thái',
      appearanceTab: 'Giao diện Uri & Ngôn ngữ',
      networkTab: 'Kết nối',
      shortcutsTab: 'Phím tắt bàn phím',
      themeLabel: 'Chế độ Giao diện',
      themeLight: 'Giao diện Sáng (Light)',
      themeDark: 'Giao diện Tối (Dark)',
      languageLabel: 'Ngôn ngữ hiển thị',
      statusLabel: 'Trạng thái hoạt động',
      customStatusLabel: 'Trạng thái tùy chỉnh',
      customStatusPlaceholder: 'Đang làm việc trên Uri...',
      saveStatus: 'Cập nhật trạng thái',
      udpSimulationLabel: 'Kiểm tra khi kết nối yếu',
      connectionModeLabel: 'Kết nối hiện tại',
      logoutButton: 'Đăng xuất tài khoản',
      closeButton: 'Đóng (Esc)',
    },
    commandPalette: {
      placeholder: 'Tìm kiếm kênh, bạn bè hoặc hành động...',
      emptyState: 'Không tìm thấy kênh, thành viên hoặc tác vụ phù hợp.',
      groupNavigation: 'Chuyển nhanh Kênh & Máy chủ',
      groupActions: 'Tác vụ Nhanh',
      groupStatus: 'Đổi Trạng thái Hoạt động',
      toggleTheme: 'Chuyển đổi Giao diện Sáng / Tối',
      toggleMonitor: 'Mở bảng Trạng thái Kết nối',
      openSettings: 'Mở Cài đặt & Hồ sơ',
      openFriends: 'Mở danh sách Bạn bè & Tin nhắn riêng',
      probeRtt: 'Kiểm tra tốc độ kết nối',
    },
  },
  en: {
    appName: 'Uri',
    appSubtitle: 'Modern Team Chat & Collaboration Workspace',
    auth: {
      welcomeTitle: 'Connect and chat effortlessly on Uri',
      welcomeSubtitle:
        'Direct messages, community channels, media sharing, and voice notes brought together in a clean, intuitive workspace.',
      loginTab: 'Sign In',
      registerTab: 'Create Account',
      usernameLabel: 'Username',
      usernamePlaceholder: 'Enter your username',
      passwordLabel: 'Password',
      passwordPlaceholder: 'Enter your password',
      displayNameLabel: 'Display Name',
      displayNamePlaceholder: 'e.g. Bui Ha Yen Vi',
      loginButton: 'Sign In to Uri',
      registerButton: 'Create Account & Start',
      backendStatusLive: 'Online',
    },
    workspace: {
      directMessages: 'Direct Messages',
      friends: 'Friends',
      textChannels: 'TEXT CHANNELS',
      voiceChannels: 'VOICE & VIDEO',
      onlineMembers: 'ONLINE',
      offlineMembers: 'OFFLINE',
      searchPlaceholder: 'Search messages...',
      pinnedMessages: 'Pinned Messages',
      protocolMonitor: 'Connection Status',
      commandPaletteHint: 'Find anything quickly (Ctrl+K)',
      messagePlaceholder: 'Message channel',
      replyingTo: 'Replying to',
      editingMessage: 'Editing message',
      typingSuffix: 'is typing',
    },
    settings: {
      title: 'Uri Workspace & Profile Settings',
      profileTab: 'Profile & Status',
      appearanceTab: 'Uri Appearance & Locale',
      networkTab: 'Connection',
      shortcutsTab: 'Keyboard Shortcuts',
      themeLabel: 'Interface Theme',
      themeLight: 'Light appearance',
      themeDark: 'Dark appearance',
      languageLabel: 'Display Language',
      statusLabel: 'Status',
      customStatusLabel: 'Custom Status',
      customStatusPlaceholder: 'Share what you are working on...',
      saveStatus: 'Update Status',
      udpSimulationLabel: 'Test a weak connection',
      connectionModeLabel: 'Current connection',
      logoutButton: 'Log Out',
      closeButton: 'Close (Esc)',
    },
    commandPalette: {
      placeholder: 'Search channels, friends, or actions...',
      emptyState: 'No matching channels, friends, or actions found.',
      groupNavigation: 'Go to Teams & Channels',
      groupActions: 'Quick Actions',
      groupStatus: 'Change Status',
      toggleTheme: 'Switch between light and dark appearance',
      toggleMonitor: 'Open connection status',
      openSettings: 'Open Uri Workspace & Profile Settings',
      openFriends: 'Open Friends & Messages',
      probeRtt: 'Check connection speed',
    },
  },
};

export function getTranslation(locale: Locale): TranslationDictionary {
  return TRANSLATIONS[locale] ?? TRANSLATIONS.vi;
}
