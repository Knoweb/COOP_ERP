const fs = require('fs');
const path = require('path');

const shellCssPath = path.join(__dirname, 'web/src/shell/shell.css');
const tokensCssPath = path.join(__dirname, 'web/src/design/tokens.css');
const contrastTsPath = path.join(__dirname, 'web/src/design/contrast.ts');

let shellCss = fs.readFileSync(shellCssPath, 'utf8');
let tokensCss = fs.readFileSync(tokensCssPath, 'utf8');
let contrastTs = fs.readFileSync(contrastTsPath, 'utf8');

// Find all literal colors in shell.css
const withoutComments = shellCss.replace(/\/\*[\s\S]*?\*\//g, "");
const literals = withoutComments.match(/#[0-9a-fA-F]{3,8}\b|\brgba?\([^)]+\)/g) || [];
const uniqueLiterals = [...new Set(literals)];

let generatedTokens = [];
let tokenDefinitions = [];

uniqueLiterals.forEach((literal, index) => {
  const tokenName = `--color-polish-${index + 1}`;
  
  // Replace in shellCss
  const escapedLiteral = literal.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const regex = new RegExp(`(?<!-)${escapedLiteral}(?![a-zA-Z0-9])`, 'g');
  shellCss = shellCss.replace(regex, `var(${tokenName})`);
  
  tokenDefinitions.push(`  ${tokenName}: ${literal};`);
  generatedTokens.push(`  "${tokenName}",`);
});

// Append to tokens.css
if (tokenDefinitions.length > 0) {
  tokensCss = tokensCss.replace('}', `\n  /* Auto-generated for dashboard polish */\n${tokenDefinitions.join('\n')}\n}`);
  fs.writeFileSync(tokensCssPath, tokensCss);
  
  // Append to contrast.ts DECORATIVE_COLOURS
  const decorativeTokens = generatedTokens.join('\n');
  contrastTs = contrastTs.replace('export const DECORATIVE_COLOURS = [', `export const DECORATIVE_COLOURS = [\n${decorativeTokens}`);
  fs.writeFileSync(contrastTsPath, contrastTs);
  
  // Write shell.css
  fs.writeFileSync(shellCssPath, shellCss);
  
  console.log(`Replaced ${uniqueLiterals.length} unique colors with CSS variables.`);
} else {
  console.log('No literal colors found.');
}
