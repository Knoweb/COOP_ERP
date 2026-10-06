// The kernel's rule for a field name that may not travel in an event payload, ported to the
// browser so that the shell can refuse to keep such a field in session storage while a step-up
// takes the person to sign in again (CR-30-2, shell/api/pendingCommand.ts).
//
// It is a COPY of kernel/internal/event/OutboxWriter.java (FORBIDDEN_WORDS, ALLOWED_FIELDS,
// SECRET_WORDS and isForbiddenField), and forbiddenFields.test.ts reads that Java file and fails
// when the lists differ. Change the Java rule and this file together; the test says which.

/** Words that name personal or secret data, matched as whole words of the field name. */
export const FORBIDDEN_WORDS: ReadonlySet<string> = new Set([
  "name",
  "firstname",
  "lastname",
  "surname",
  "fullname",
  "phone",
  "mobile",
  "telephone",
  "msisdn",
  "whatsapp",
  "email",
  "mail",
  "address",
  "street",
  "city",
  "nic",
  "passport",
  "licence",
  "license",
  "password",
  "pin",
  "otp",
  "token",
  "secret",
  "credential",
  "dob",
  "birthdate",
  "dateofbirth",
  "birthday"
]);

/** The display names of catalogue things (a product or a unit, never a person), lower case, no underscores. */
export const ALLOWED_FIELDS: ReadonlySet<string> = new Set(["nameen", "namesi", "nameta", "uomname", "productname"]);

/** Words that stay refused even at the head of an identifier: a PIN code is still a PIN. */
export const SECRET_WORDS: ReadonlySet<string> = new Set(["pin", "otp", "password", "token", "secret", "credential", "nic", "passport"]);

/** True when a field of that name would carry personal or secret data (OutboxWriter.isForbiddenField). */
export function isForbiddenField(key: string): boolean {
  const words = key
    .replace(/([a-z0-9])([A-Z])/g, "$1_$2")
    .toLowerCase()
    .split(/[^a-z0-9]+/);
  // Java's split drops trailing empty strings ("name_" is one word); JavaScript's keeps them.
  while (words.length > 1 && words[words.length - 1] === "") {
    words.pop();
  }
  if (ALLOWED_FIELDS.has(words.join(""))) {
    return false;
  }
  // An identifier or a code refers to a row, it does not carry the value (addressId, cityCode),
  // unless its head is a secret (pinCode, tokenId).
  const last = words[words.length - 1] ?? "";
  if (words.length > 1 && (last === "id" || last === "code")) {
    return words.some((word) => SECRET_WORDS.has(word));
  }
  if (words.some((word) => FORBIDDEN_WORDS.has(word))) {
    return true;
  }
  // Joined forms a split cannot see: "firstname", "dateofbirth", "emailaddress".
  const joined = words.join("");
  for (const forbidden of FORBIDDEN_WORDS) {
    if (forbidden.length >= 5 && joined.includes(forbidden)) {
      return true;
    }
  }
  return false;
}

/** True when any key, at any depth, of a parsed JSON value is a forbidden field. */
export function hasForbiddenField(value: unknown): boolean {
  if (Array.isArray(value)) {
    return value.some(hasForbiddenField);
  }
  if (value !== null && typeof value === "object") {
    return Object.entries(value).some(([key, child]) => isForbiddenField(key) || hasForbiddenField(child));
  }
  return false;
}
