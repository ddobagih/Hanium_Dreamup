const KOREA_TIME_OFFSET = "+09:00";

export function koreanCalendarDayStart(value: string): string | undefined {
  return value ? `${value}T00:00:00${KOREA_TIME_OFFSET}` : undefined;
}

export function koreanCalendarDayEnd(value: string): string | undefined {
  return value ? `${value}T23:59:59.999999${KOREA_TIME_OFFSET}` : undefined;
}
