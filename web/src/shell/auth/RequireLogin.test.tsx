import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { IntlProvider } from "react-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../i18n/messages";
import { RequireLogin } from "./RequireLogin";

const auth = {
  isAuthenticated: false,
  isLoading: false,
  activeNavigator: undefined as string | undefined,
  error: undefined as Error | undefined,
  signinRedirect: vi.fn(() => Promise.resolve())
};

vi.mock("react-oidc-context", () => ({
  useAuth: () => auth,
  hasAuthParams: () => false
}));

function renderGate(locale: "en" | "ta" = "en") {
  return render(
    <IntlProvider locale={locale} messages={messages[locale]}>
      <RequireLogin>
        <p>the back office</p>
      </RequireLogin>
    </IntlProvider>
  );
}

describe("RequireLogin", () => {
  beforeEach(() => {
    Object.assign(auth, { isAuthenticated: false, isLoading: false, activeNavigator: undefined, error: undefined });
    auth.signinRedirect.mockClear();
    window.history.replaceState({}, "", "/hello?lang=ta");
  });
  afterEach(cleanup);

  it("sends an anonymous visitor to the identity server and remembers the page asked for", () => {
    renderGate("ta");

    expect(auth.signinRedirect).toHaveBeenCalledWith({ state: { returnTo: "/hello?lang=ta" } });
    expect(screen.queryByText("the back office")).toBeNull();
    expect(screen.getByText("உங்களை உள்நுழைக்கிறது …")).toBeTruthy();
  });

  it("shows the application to somebody who is signed in", () => {
    auth.isAuthenticated = true;
    renderGate();

    expect(screen.getByText("the back office")).toBeTruthy();
    expect(auth.signinRedirect).not.toHaveBeenCalled();
  });

  it("does not start a second login while one is under way", () => {
    auth.isLoading = true;
    renderGate();

    expect(auth.signinRedirect).not.toHaveBeenCalled();
  });

  it("says that sign-in failed and retries only when asked, never by itself", () => {
    auth.error = new Error("Failed to fetch");
    renderGate();

    expect(screen.getByRole("alert").textContent).toContain("Sign-in did not work");
    expect(auth.signinRedirect).not.toHaveBeenCalled();

    fireEvent.click(screen.getByText("Try again"));
    expect(auth.signinRedirect).toHaveBeenCalledTimes(1);
  });
});
