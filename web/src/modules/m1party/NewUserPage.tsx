import { useState } from "react";
import type { FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import "./m1party.css";
import { useT } from "../../shell/i18n/useT";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { PageHeader } from "../../shell/components/PageHeader";
import { useScope } from "../../shell/scope/useScope";
import { useAdminApi } from "./adminApi";
import type { CreateUserRequest, UserKind } from "./adminApi";
import { refusalOf } from "./adminView";
import { CustomSelect } from "./CustomSelect";

const LANGUAGES: CreateUserRequest["language"][] = ["en", "si", "ta"];
// EXTERNAL is the Federation's alone and set up with a grant (M1-09); it is not offered here.
const KINDS: UserKind[] = ["BACK_OFFICE", "TILL", "BOTH"];

/**
 * Creating a user of the caller's entity (21A M1-07, CreateUser). The user starts PENDING with
 * no credential, as the slice says: the next step, on the user's card, is to issue the first
 * password and give the user a role at a location. Where the user may work is not a field of
 * the user: it is the location of each role assignment (21A section 3, user_role), so it is
 * chosen on the card with the role. The entity is the one the administrator works for (the
 * scope banner names it); the request leaves homeEntityId out and the server fills it in.
 */
export function NewUserPage() {
  const t = useT();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const api = useAdminApi();
  const scope = useScope();
  const idempotencyKey = useIdempotencyKey();

  const [form, setForm] = useState<CreateUserRequest>({ username: "", displayName: "", language: "en", userKind: "BACK_OFFICE" });
  const set = <K extends keyof CreateUserRequest>(key: K, value: CreateUserRequest[K]) => setForm((current) => ({ ...current, [key]: value }));

  const create = useMutation({
    mutationFn: () =>
      api.createUser({ ...form, username: form.username.trim(), displayName: form.displayName.trim() }, idempotencyKey.current()),
    onSuccess: (user) => {
      idempotencyKey.next();
      queryClient.invalidateQueries({ queryKey: ["party", "users"] });
      navigate(`/party/users/${user.userId}`);
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        idempotencyKey.next();
      }
    }
  });
  const fieldErrors = create.error instanceof ApiProblem ? create.error.fieldErrors : {};

  const submit = (event: FormEvent) => {
    event.preventDefault();
    create.mutate();
  };

  return (
    <main className="shell-page new-user-page">
      <p className="new-user-back-wrap">
        <Link className="back-link" to="/party/users">
          <svg
            viewBox="0 0 24 24"
            width="18"
            height="18"
            stroke="currentColor"
            strokeWidth="2"
            fill="none"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M19 12H5" />
            <path d="m12 19-7-7 7-7" />
          </svg>
          <span>{t("party.users.back").text}</span>
        </Link>
      </p>
      <PageHeader icon="society" title={t("party.users.new.title").text} />
      <p className="new-user-hint">{t("party.users.new.hint").text}</p>

      <form onSubmit={submit} className="party-form-row new-user-form">
        <p className="new-user-entity-info">
          {t("party.users.new.entity").text} <strong>{scope.entityName ?? scope.entityShortId ?? ""}</strong>
        </p>
        <label className="party-form-field new-user-field">
          <span className="new-user-field__label">{t("party.user.username").text}</span>
          <input
            className="new-user-input"
            value={form.username}
            onChange={(event) => set("username", event.target.value)}
            required
            maxLength={64}
            autoComplete="off"
          />
          {fieldErrors.username && <span className="party-alert-text">{fieldErrors.username}</span>}
        </label>
        <label className="party-form-field new-user-field">
          <span className="new-user-field__label">{t("party.user.display_name").text}</span>
          <input
            className="new-user-input"
            value={form.displayName}
            onChange={(event) => set("displayName", event.target.value)}
            required
            maxLength={120}
          />
          {fieldErrors.displayName && <span className="party-alert-text">{fieldErrors.displayName}</span>}
        </label>
        <label className="party-form-field new-user-field">
          <span className="new-user-field__label">{t("party.field.language").text}</span>
          <CustomSelect
            value={form.language}
            onChange={(language) => set("language", language)}
            ariaLabel={t("party.field.language").text}
            options={LANGUAGES.map((language) => ({
              value: language,
              label: t(`party.language.${language}`).text
            }))}
          />
        </label>
        <label className="party-form-field new-user-field">
          <span className="new-user-field__label">{t("party.user.kind").text}</span>
          <CustomSelect
            value={form.userKind}
            onChange={(userKind) => set("userKind", userKind)}
            ariaLabel={t("party.user.kind").text}
            options={KINDS.map((kind) => ({
              value: kind,
              label: t(`party.user.kind.${kind}`).text
            }))}
          />
        </label>

        <button
          type="submit"
          className="modern-btn new-user-submit-btn"
          disabled={create.isPending || !form.username.trim() || !form.displayName.trim()}
        >
          <svg
            viewBox="0 0 24 24"
            width="18"
            height="18"
            stroke="currentColor"
            strokeWidth="2.5"
            fill="none"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <polyline points="20 6 9 17 4 12" />
          </svg>
          <span>{create.isPending ? t("party.users.new.submitting").text : t("party.users.new.submit").text}</span>
        </button>
        {create.isError && <p role="alert">{refusalOf(create.error, t("party.error.generic").text).text}</p>}
      </form>
    </main>
  );
}
