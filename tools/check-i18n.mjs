import fs from "node:fs";
import path from "node:path";

const base = path.resolve(
    "backend/app/src/main/resources/i18n"
);

const languages = ["en", "si", "ta"];

function readCatalogue(language) {
    const file = path.join(base, `${language}.json`);

    if (!fs.existsSync(file)) {
        throw new Error(`Missing i18n file: ${file}`);
    }

    return JSON.parse(
        fs.readFileSync(file, "utf8")
    );
}

const catalogues = Object.fromEntries(
    languages.map(language => [
        language,
        readCatalogue(language)
    ])
);

const allKeys = new Set(
    languages.flatMap(language =>
        Object.keys(catalogues[language])
    )
);

let failed = false;

for (const key of [...allKeys].sort()) {
    for (const language of languages) {
        if (!(key in catalogues[language])) {
            console.error(
                `Missing translation: ${language}.json -> ${key}`
            );

            failed = true;
        }
    }
}

if (failed) {
    process.exit(1);
}

console.log(
    `i18n check passed: ${allKeys.size} message id(s) present in en/si/ta`
);