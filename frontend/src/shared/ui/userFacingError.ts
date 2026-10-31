type UserFacingErrorOptions = {
  action: string;
  locale?: 'vi' | 'en';
  fallback?: string;
  fallbackEn?: string;
};

/** Keep transport/server wording out of the interface while preserving diagnostics in the console. */
export function userFacingError(
  error: unknown,
  { action, locale = 'vi', fallback, fallbackEn }: UserFacingErrorOptions,
): string {
  console.error(`[Uri] ${action} failed`, error);

  const detail = error instanceof Error
    ? `${error.name} ${error.message}`
    : String(error ?? '');
  const normalized = detail.toLocaleLowerCase(locale);
  const isVi = locale === 'vi';
  const genericFallback = isVi ? 'Đã xảy ra sự cố. Vui lòng thử lại.' : 'Something went wrong. Please try again.';

  if (/notallowederror|microphone|micro access|microphone access/.test(normalized)) {
    return isVi ? 'Không thể sử dụng micro. Hãy cho phép Uri dùng micro rồi thử lại.' : 'Uri cannot use your microphone. Allow access and try again.';
  }
  if (/invalid credentials|invalid username or password|incorrect password|wrong password|authentication failed|auth_failed|auth_required|unauthenticated|token expired|invalid token|unauthorized/.test(normalized)) {
    return isVi ? 'Tên đăng nhập hoặc mật khẩu chưa đúng, hoặc phiên đăng nhập đã hết hạn.' : 'Your username or password is incorrect, or your sign-in has expired.';
  }
  if (/forbidden|permission|access denied|not allowed|insufficient role/.test(normalized)) {
    return isVi ? 'Bạn chưa có quyền thực hiện thao tác này.' : 'You do not have permission to do this.';
  }
  if (/not found|not_found|invite_expired|invite code is invalid|does not exist|unknown user|unknown channel|unknown conversation/.test(normalized)) {
    return isVi ? 'Không tìm thấy nội dung cần mở. Hãy tải lại rồi thử lại.' : 'We could not find that. Refresh and try again.';
  }
  if (/conflict|already exists|duplicate|username_taken|room_name_taken|already a friend|already sent|already joined|in use/.test(normalized)) {
    return isVi ? 'Thông tin này đã được sử dụng hoặc thao tác đã được thực hiện.' : 'This information is already in use, or the action is already complete.';
  }
  if (/validation|invalid|required|must be|too long|too large|too_large|payload_too_large|exceeds|bad request/.test(normalized)) {
    return isVi ? 'Thông tin chưa hợp lệ. Vui lòng kiểm tra lại.' : 'Please check the information and try again.';
  }
  if (/rate_limit|too many requests/.test(normalized)) {
    return isVi ? 'Bạn thao tác quá nhanh. Vui lòng chờ một chút rồi thử lại.' : 'You are doing that too quickly. Wait a moment and try again.';
  }
  if (/offline|not connected|connection|network|timeout|timed out|failed to fetch|websocket|socket|econnrefused|server did not confirm/.test(normalized)) {
    return isVi ? 'Kết nối đang gián đoạn. Hãy kiểm tra mạng rồi thử lại.' : 'Your connection was interrupted. Check your network and try again.';
  }

  return isVi ? fallback ?? genericFallback : fallbackEn ?? genericFallback;
}
