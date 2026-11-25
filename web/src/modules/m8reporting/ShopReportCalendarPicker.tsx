import { ReportingDatePicker, ReportingMonthPicker } from "./reportingPickers";

export interface ShopReportCalendarProps {
  id: string;
  label: string;
  value: string;
  onChange: (nextValue: string) => void;
}

export function ShopReportDatePicker(props: ShopReportCalendarProps) {
  return <ReportingDatePicker {...props} />;
}

export function ShopReportMonthPicker(props: ShopReportCalendarProps) {
  return <ReportingMonthPicker {...props} />;
}
