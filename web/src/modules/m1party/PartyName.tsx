import { useIntl } from "react-intl";
import { useQuery } from "@tanstack/react-query";
import { usePartyApi } from "./partyApi";
import { legalNameIn } from "./societyView";

/** An entity's legal name in the user's language, read from M1; its id until it arrives. */
export function PartyName({ entityId }: { entityId: string }) {
  const api = usePartyApi();
  const { locale } = useIntl();
  const entity = useQuery({ queryKey: ["party", "society", entityId], queryFn: () => api.getSociety(entityId), retry: false });
  return <>{entity.data ? (legalNameIn(entity.data, locale) ?? entity.data.legalNameEn) : entityId}</>;
}
