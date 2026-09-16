import fs from "node:fs";
import path from "node:path";

const migrationRoot = path.resolve(
    "backend/app/src/main/resources/db/migration"
);

const ownership = {
    kernel: ["kernel"],
    hello: ["hello"],
    m1party: ["party", "security"],
    m2catalogue: ["catalogue"],
    m3pricing: ["pricing"],
    m4trading: ["trading"],
    m5inventory: ["inventory"],
    m6pos: ["pos"],
    m7customers: ["customers"],
    m8reporting: ["reporting"],
    m9integration: ["integration"]
};

const ddlPatterns = [
    /\bCREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?["']?([a-z_][a-z0-9_]*)["']?\./gi,
    /\bALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?["']?([a-z_][a-z0-9_]*)["']?\./gi,
    /\bDROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?["']?([a-z_][a-z0-9_]*)["']?\./gi,
    /\bTRUNCATE\s+(?:TABLE\s+)?["']?([a-z_][a-z0-9_]*)["']?\./gi,
    /\bINSERT\s+INTO\s+["']?([a-z_][a-z0-9_]*)["']?\./gi,
    /\bUPDATE\s+["']?([a-z_][a-z0-9_]*)["']?\./gi,
    /\bDELETE\s+FROM\s+["']?([a-z_][a-z0-9_]*)["']?\./gi,
    /\bCREATE\s+(?:UNIQUE\s+)?INDEX\b[\s\S]*?\bON\s+["']?([a-z_][a-z0-9_]*)["']?\./gi
];

const foreignKeyPattern =
    /\bREFERENCES\s+["']?([a-z_][a-z0-9_]*)["']?\./gi;

let failed = false;

for (const [module, allowedSchemas] of Object.entries(ownership)) {

    const folder = path.join(
        migrationRoot,
        module
    );

    if (!fs.existsSync(folder)) {
        continue;
    }

    const files = fs.readdirSync(folder)
        .filter(file => file.endsWith(".sql"));

    for (const file of files) {

        const fullPath = path.join(folder, file);

        // Sprint 0 kernel baseline intentionally creates every module schema.
        if (
            module === "kernel"
            && file === "V0001__baseline.sql"
        ) {
            continue;
        }

        const sql =
            fs.readFileSync(fullPath, "utf8");

        for (const pattern of ddlPatterns) {

            pattern.lastIndex = 0;

            let match;

            while ((match = pattern.exec(sql)) !== null) {

                const schema = match[1];

                if (!allowedSchemas.includes(schema)) {

                    console.error(
                        `${module}/${file}: migration modifies schema "${schema}", ` +
                        `allowed: ${allowedSchemas.join(", ")}`
                    );

                    failed = true;
                }
            }
        }

        foreignKeyPattern.lastIndex = 0;

        let fkMatch;

        while (
            (fkMatch = foreignKeyPattern.exec(sql)) !== null
        ) {

            const schema = fkMatch[1];

            if (!allowedSchemas.includes(schema)) {

                console.error(
                    `${module}/${file}: cross-schema foreign key references "${schema}". ` +
                    `Use an identifier only; cross-schema foreign keys are forbidden.`
                );

                failed = true;
            }
        }
    }
}

if (failed) {
    process.exit(1);
}

console.log(
    "Schema ownership check passed."
);