# Catalogue: browsing and creating shared items

**Story.** As the Federation's catalogue steward, I want to create one shared item that every society can see and price, so that the whole cooperative sells the same goods under the same name in three languages.

**Who signs in.** `fed-steward` (Nirmala Perera), password `demo`, screen in English.

## Steps

1. Sign in at http://localhost:5173, go to **Catalogue** in the left navigation.
2. Point out: the list shows every item's code, name, unit, state ("Draft", "Local", "Shared", "Inactive"). Type "rice" into **Search by code or name** — the list narrows.
3. Open "Samba rice 5 kg" from the list. Point out: the name in three scripts, the base **Unit** (EA), a pack unit (BAG of 5) under **Units**, the **Barcode**, the **Tax category** (EXEMPT), and the state **Shared**.
4. Click **Back to the catalogue**, then **New item**.
5. Fill in **Name (English)**, **Name (Sinhala)**, **Name (Tamil)** — say out loud: a shared item needs all three names before it can be shared; a local item (one society's own) does not. Choose a **Tax category**. Click **Create as draft**.
6. On the new item's card, click **Share with every society** — the state changes from "Draft" to "Shared".
7. Click **Back to the catalogue**, search by the Sinhala name you typed — the item is found, in its new "Shared" state.

## What to point out

- **Three languages by design, not by translation later**: the item cannot be shared without all three names; every society's staff reads it in their own language from the same row.
- **Everything audited**: every state change here (create, share) is a command through the Federation steward's own permission, with an audit record and a stock/pricing event other modules react to — nothing is a silent edit.
- **A shop is a location, not a legal entity**: this catalogue item, once shared, is the same row a shop 800 societies away will sell — there is one truth for what "Samba rice 5 kg" means, not one copy per warehouse.

## What can go wrong

- **Share is greyed out or refused**: the item is missing one of the three names — go back and fill the missing language, save, try again.
- **Search finds nothing by the Sinhala name right after sharing**: the search index can lag by a second or two after a fresh action; wait and retry rather than assuming it failed.
- **Signed in as the wrong user**: only `fed-steward` (or another Federation-scoped user) can share Federation-owned items; a society user only manages its own local items. If "Share with every society" is missing, check who is signed in.
