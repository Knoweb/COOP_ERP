import { useIntl } from "react-intl";
import { useQuery } from "@tanstack/react-query";
import "./m1party.css";
import { useT } from "../../shell/i18n/useT";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import { useAdminApi } from "./adminApi";
import { refusalOf, roleName } from "./adminView";

/**
 * The role catalogue (21A section 8, "Roles"; M1-08), read only: the entity's own roles first
 * and the Federation's templates last, each with the permission codes it grants. The slice
 * can also create, amend and retire a role (createRole, amendRole, retireRole); editing is left
 * for a later screen (accepted on the architect's delegation, see the pull request): it needs a
 * readable permission catalogue to pick from, which the slice does not serve yet.
 */
export function RolesPage() {
  const t = useT();
  const { locale } = useIntl();
  const api = useAdminApi();
  const roles = useQuery({ queryKey: ["party", "roles"], queryFn: () => api.listRoles() });

  return (
    <main className="shell-page">
      <PageHeader icon="society" title={t("party.roles.title").text} />
      {roles.isLoading && <p>{t("party.list.loading").text}</p>}
      {roles.isError && <p role="alert">{refusalOf(roles.error, t("party.error.generic").text).text}</p>}
      {roles.isSuccess && roles.data.length === 0 && <p>{t("party.roles.empty").text}</p>}

      {(roles.data ?? []).map((role) => {
        const name = roleName(role, locale);
        return (
          <section key={role.roleId} className="modern-table-card party-form-row-margin" aria-label={name}>
            <h2>
              {name}{" "}
              <StateChip
                state={role.status === "ACTIVE" ? "issued" : "void"}
                label={t(`party.roles.status.${role.status}`).text}
              />
            </h2>
            <p>
              {t(role.template ? "party.roles.template" : "party.roles.own").text} · {t(`party.roles.class.${role.roleClass}`).text} ·{" "}
              {t("party.roles.version", undefined, { version: role.version }).text}
            </p>
            {role.permissions.length === 0 ? (
              <p>{t("party.roles.no_permissions").text}</p>
            ) : (
              <ul aria-label={t("party.roles.permissions_of", undefined, { role: name }).text}>
                {role.permissions.map((permission) => (
                  <li key={permission.permissionCode}>
                    <code>{permission.permissionCode}</code>
                  </li>
                ))}
              </ul>
            )}
          </section>
        );
      })}
    </main>
  );
}
