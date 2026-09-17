import { useIntl } from "react-intl";

export function useT() {
  const intl = useIntl();

  return (id: string, defaultMessage?: string, values?: Record<string, any>) => {
    const text = intl.formatMessage({ id, defaultMessage }, values);
    const isFallback = intl.messages[id] === undefined;

    return {
      text,
      isFallback
    };
  };
}
