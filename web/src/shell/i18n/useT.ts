import { useIntl } from "react-intl";

/** What an ICU message can take as a value: {count} items, {name}, {when, date}. */
type MessageValue = string | number | boolean | Date | null | undefined;

export function useT() {
  const intl = useIntl();

  return (id: string, defaultMessage?: string, values?: Record<string, MessageValue>) => {
    const text = intl.formatMessage({ id, defaultMessage }, values);
    const isFallback = intl.messages[id] === undefined;

    return {
      text,
      isFallback
    };
  };
}
