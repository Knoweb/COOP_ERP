// "?raw" gives the text of tokens.css: the page shows the values the screens really use.
import tokensCss from "../../design/tokens.css?raw";
import { CONTRAST_PAIRS, contrastRatio, parseTokens } from "../../design/contrast";
import { MoneyDisplay } from "../components/MoneyDisplay";
import { CHIP_STATES, StateChip } from "../components/StateChip";
import { TrainingBadge } from "../components/TrainingBadge";
import { useT } from "../i18n/useT";

const TOKENS = parseTokens(tokensCss);
const FONT_SIZES = Object.keys(TOKENS).filter((name) => name.startsWith("--font-size-"));
const SPACES = Object.keys(TOKENS).filter((name) => name.startsWith("--space-"));

// What a server may send as an amount, the good and the bad, to show what MoneyDisplay makes of it.
const AMOUNTS: Array<string | number> = ["0", "0.5", "1234.5", 1234.5, "1234567.891", "-250.00", "-0.00", "abc", NaN];

/**
 * The living reference of the design system, at /_design: the tokens with their real values and
 * measured contrast, and every shared component in every state, in the language of the user.
 * A module developer looks here before building a screen; QA looks here to review a translation.
 *
 * It is a page of the shell, not a module: it is in no navigation, shows no data and calls no
 * API, so any signed-in user may open it. It is also the honest test of the rule it explains:
 * apart from the class names of shell.css it uses tokens only.
 */
export function DesignPage() {
  const t = useT();

  return (
    <main className="shell-page design-page">
      <h1>{t("shell.design.title").text}</h1>
      <p>{t("shell.design.intro").text}</p>

      <section>
        <h2>{t("shell.design.type.title").text}</h2>
        <table>
          <thead>
            <tr>
              <th scope="col">{t("shell.design.col.token").text}</th>
              <th scope="col">{t("shell.design.col.value").text}</th>
              <th scope="col">{t("shell.design.col.example").text}</th>
            </tr>
          </thead>
          <tbody>
            {FONT_SIZES.map((name) => (
              <tr key={name}>
                <td><code>{name}</code></td>
                <td>{TOKENS[name]}</td>
                <td style={{ fontSize: `var(${name})` }}>{t("shell.design.sample").text}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section>
        <h2>{t("shell.design.space.title").text}</h2>
        <table>
          <thead>
            <tr>
              <th scope="col">{t("shell.design.col.token").text}</th>
              <th scope="col">{t("shell.design.col.value").text}</th>
              <th scope="col">{t("shell.design.col.example").text}</th>
            </tr>
          </thead>
          <tbody>
            {[...SPACES, "--target-gap", "--target-min"].map((name) => (
              <tr key={name}>
                <td><code>{name}</code></td>
                <td>{TOKENS[name]}</td>
                <td><div className="design-page__bar" style={{ width: `var(${name})` }} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section>
        <h2>{t("shell.design.colour.title").text}</h2>
        <p>{t("shell.design.colour.text").text}</p>
        <table>
          <thead>
            <tr>
              <th scope="col">{t("shell.design.col.token").text}</th>
              <th scope="col">{t("shell.design.col.example").text}</th>
              <th scope="col">{t("shell.design.col.contrast").text}</th>
              <th scope="col">{t("shell.design.col.required").text}</th>
            </tr>
          </thead>
          <tbody>
            {CONTRAST_PAIRS.map((pair) => (
              <tr key={`${pair.foreground} ${pair.background}`}>
                <td>
                  <code>{pair.foreground}</code>
                  <br />
                  <code>{pair.background}</code>
                </td>
                <td>
                  <span
                    className="design-page__swatch"
                    style={{ color: `var(${pair.foreground})`, background: `var(${pair.background})` }}
                  >
                    {t("shell.design.sample").text}
                  </span>
                </td>
                <td className="money">{contrastRatio(TOKENS[pair.foreground], TOKENS[pair.background]).toFixed(2)}:1</td>
                <td className="money">{pair.minimum}:1</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section>
        <h2>{t("shell.design.components.title").text}</h2>

        <h3><code>MoneyDisplay</code></h3>
        <table>
          <thead>
            <tr>
              <th scope="col">{t("shell.design.col.input").text}</th>
              <th scope="col">{t("shell.design.col.shown").text}</th>
            </tr>
          </thead>
          <tbody>
            {AMOUNTS.map((amount, index) => (
              <tr key={index}>
                <td><code>{typeof amount === "string" ? `"${amount}"` : String(amount)}</code></td>
                <td style={{ textAlign: "right" }}><MoneyDisplay amount={amount} /></td>
              </tr>
            ))}
          </tbody>
        </table>
        <p><MoneyDisplay amount="1234.50" size="total" /></p>

        <h3><code>StateChip</code></h3>
        <p className="design-page__row">
          {CHIP_STATES.map((state) => (
            <StateChip key={state} state={state} />
          ))}
          {/* A module's own word on one of the five looks. */}
          <StateChip state="issued" label={t("shell.design.components.chip_own_label").text} />
        </p>

        <h3><code>TrainingBadge</code></h3>
        <TrainingBadge active={true} />
        <p>{t("shell.design.components.training_off").text}</p>
      </section>
    </main>
  );
}
