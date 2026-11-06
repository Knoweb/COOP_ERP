// Canonical permission labels and descriptions derived from the M1 security catalogue
// (backend/app/src/main/resources/seed/m1party/permissions.yaml; 21A M1-08).

export interface PermissionMeta {
  label: string;
  description: string;
}

export const PERMISSION_CATALOGUE: Record<string, PermissionMeta> = {
  "gov.entity.register": {
    label: "Register entity",
    description: "Register federation-managed entities"
  },
  "gov.entity.activate": {
    label: "Activate entity",
    description: "Activate federation-managed entities"
  },
  "gov.entity.suspend": {
    label: "Suspend entity",
    description: "Suspend and reinstate federation-managed entities"
  },
  "gov.entity.view": {
    label: "View entity records",
    description: "View federation entity records"
  },
  "gov.user.manage": {
    label: "Manage users",
    description: "Manage user credentials and status"
  },
  "gov.user.view": {
    label: "View users",
    description: "View the users of the entity or the shop"
  },
  "gov.role.manage": {
    label: "Manage roles & permissions",
    description: "Manage roles and assignments"
  },
  "gov.external.grant": {
    label: "Grant external access",
    description: "Grant timeboxed access to external regulators"
  },
  "gov.audit.review": {
    label: "Review audit log",
    description: "Review the audit trail of the entity"
  },
  "prt.relationship.view": {
    label: "View relationships",
    description: "View trading relationships"
  },
  "prt.relationship.open": {
    label: "Open relationship draft",
    description: "Open a trading relationship in draft"
  },
  "prt.relationship.activate": {
    label: "Activate relationship",
    description: "Activate a draft trading relationship"
  },
  "prt.relationship.amend": {
    label: "Amend relationship terms",
    description: "Amend the terms of a trading relationship"
  },
  "prt.relationship.suspend": {
    label: "Suspend relationship",
    description: "Suspend a trading relationship"
  },
  "bil.creditlimit.change": {
    label: "Change credit limit",
    description: "Change the credit limit of a trading relationship"
  },
  "prt.location.view": {
    label: "View locations",
    description: "View location details"
  },
  "prt.location.register": {
    label: "Register location",
    description: "Register locations and update their details"
  },
  "prt.location.activate": {
    label: "Activate location",
    description: "Confirm connectivity and move locations through onboarding, active and dormant"
  },
  "prt.location.primary": {
    label: "Name primary shop till",
    description: "Name the primary till of a shop"
  },
  "prt.position.manage": {
    label: "Manage till positions",
    description: "Register and retire till positions"
  },
  "sys.device.view": {
    label: "View POS devices",
    description: "View device status"
  },
  "sys.device.enrol": {
    label: "Enrol POS device",
    description: "Enrol devices and assign them to till positions"
  },
  "sys.device.suspend": {
    label: "Suspend POS device",
    description: "Suspend, reinstate and retire devices"
  },
  "prc.pricelist.view": {
    label: "View price lists",
    description: "View price lists and trade prices"
  },
  "prc.pricelist.author": {
    label: "Draft price lists",
    description: "Draft price lists and set their lines"
  },
  "prc.pricelist.publish": {
    label: "Publish price lists",
    description: "Publish price lists"
  },
  "prc.rule.author": {
    label: "Author discount rules",
    description: "Author discount rules"
  },
  "prc.rule.activate": {
    label: "Activate discount rules",
    description: "Activate and withdraw discount rules"
  },
  "prc.controlprice.enter": {
    label: "Enter control price",
    description: "Enter and rescind gazetted control prices"
  },
  "prc.mrp_policy.set": {
    label: "Set MRP policy",
    description: "Set the multi-MRP policy of an item"
  },
  "prc.review.view": {
    label: "View pricing reviews",
    description: "View pricing reviews"
  },
  "prc.adjust.special": {
    label: "Apply special discounts",
    description: "Apply special discounts to orders"
  },
  "prc.adjust.delivery": {
    label: "Apply delivery fees",
    description: "Apply delivery fees to orders"
  },
  "prc.label.print": {
    label: "Print shelf price labels",
    description: "Print shelf price labels"
  },
  "cat.sku.view": {
    label: "View catalogue SKU",
    description: "View catalogue items visible in the current scope"
  },
  "cat.sku.create": {
    label: "Create catalogue SKU",
    description: "Create shared catalogue items"
  },
  "cat.sku.create_local": {
    label: "Create local SKU",
    description: "Create and activate local catalogue items"
  },
  "cat.sku.promote": {
    label: "Promote local SKU",
    description: "Promote or merge local items into the shared catalogue"
  },
  "cat.sku.deactivate": {
    label: "Deactivate SKU",
    description: "Deactivate and reactivate catalogue items"
  },
  "cat.barcode.manage": {
    label: "Manage barcodes",
    description: "Register, retire and link barcodes"
  },
  "cat.image.manage": {
    label: "Manage item images",
    description: "Attach and retire item images"
  },
  "cat.tag.manage": {
    label: "Manage tags",
    description: "Define the entity's own tags and tag its items"
  },
  "cat.tag.govern": {
    label: "Govern tags",
    description: "Define governed tags and put them on shared items"
  },
  "cat.tax.publish": {
    label: "Publish tax rates",
    description: "Define tax categories and publish tax rates"
  },
  "cat.batch.correct": {
    label: "Correct batch MRP/expiry",
    description: "Correct the MRP or expiry of a batch"
  },
  "cat.supplier.manage": {
    label: "Manage suppliers",
    description: "Register suppliers"
  },
  "cat.import.run": {
    label: "Import item sheets",
    description: "Import item sheets"
  },
  "cat.department.manage": {
    label: "Manage departments",
    description: "Define and manage merchandise department categories"
  },
  "trd.document.view": {
    label: "View trading documents",
    description: "View trading documents visible in the current scope"
  },
  "ord.order.draft": {
    label: "Draft order",
    description: "Draft orders to a seller"
  },
  "ord.order.submit": {
    label: "Submit order",
    description: "Submit, amend and cancel the entity's own orders"
  },
  "ord.order.accept": {
    label: "Accept order",
    description: "Accept, allocate and reject orders received as the seller"
  },
  "ord.order.adjust": {
    label: "Adjust order lines",
    description: "Record adjustments on orders"
  },
  "ord.order.lock_override": {
    label: "Override order lock",
    description: "Override the lock window of an order"
  },
  "del.note.draft": {
    label: "Draft delivery note",
    description: "Draft delivery notes with drops"
  },
  "del.note.issue": {
    label: "Issue delivery note",
    description: "Issue delivery notes"
  },
  "del.note.dispatch": {
    label: "Dispatch delivery note",
    description: "Dispatch delivery notes and close undelivered drops"
  },
  "del.pod.record": {
    label: "Record POD (Delivery)",
    description: "Record proof of delivery at a drop"
  },
  "shop.grn.confirm": {
    label: "Confirm shop GRN",
    description: "Capture and confirm goods received at a shop"
  },
  "whs.grn.confirm": {
    label: "Confirm warehouse GRN",
    description: "Capture and confirm goods received at a warehouse"
  },
  "shop.grn.reverse": {
    label: "Reverse shop GRN",
    description: "Reverse a confirmed goods received note within the window"
  },
  "del.discrepancy.resolve": {
    label: "Resolve discrepancy",
    description: "Acknowledge, propose and accept discrepancy resolutions"
  },
  "del.discrepancy.escalate": {
    label: "Discrepancy escalate",
    description: "Escalate a discrepancy to the Federation arbiter"
  },
  "del.claim.raise": {
    label: "Claim raise",
    description: "Raise claims against received goods"
  },
  "del.claim.inspect": {
    label: "Claim inspect",
    description: "Inspect claims received as the seller"
  },
  "del.claim.decide": {
    label: "Decide delivery claim",
    description: "Approve or reject inspected claims"
  },
  "del.claim.escalate": {
    label: "Claim escalate",
    description: "Escalate a claim to the Federation arbiter"
  },
  "fed.arbitrate": {
    label: "Arbitrate disputes (Federation)",
    description: "Arbitrate escalated discrepancies and claims"
  },
  "bil.invoice.issue": {
    label: "Issue invoice",
    description: "Issue tax invoices from confirmed goods received notes"
  },
  "bil.invoice.dispute": {
    label: "Dispute invoice",
    description: "Dispute an invoice received as the buyer"
  },
  "bil.creditnote.issue": {
    label: "Issue credit note",
    description: "Issue and apply credit notes"
  },
  "bil.debitnote.issue": {
    label: "Issue debit note",
    description: "Issue debit notes"
  },
  "bil.payment.record": {
    label: "Record payment",
    description: "Record payment receipts, settlements and cheque outcomes"
  },
  "bil.payment.reverse": {
    label: "Reverse payment",
    description: "Reverse a payment receipt"
  },
  "bil.statement.generate": {
    label: "Generate statement",
    description: "Generate statements of account"
  },
  "shop.transfer.request": {
    label: "Request shop transfer",
    description: "Request a lateral transfer from another shop"
  },
  "mpcs.transfer.approve": {
    label: "Approve MPCS transfer",
    description: "Approve or reject lateral transfer requests"
  },
  "inv.stock.view": {
    label: "View stock balances",
    description: "View stock balances, availability and movements"
  },
  "inv.stock.receive": {
    label: "Receive stock (GRN)",
    description: "See the stock a confirmed goods received note put into the location"
  },
  "inv.count.schedule": {
    label: "Schedule stock count",
    description: "Schedule stock counts and acknowledge negative lots"
  },
  "shop.count.record": {
    label: "Record shop stock count",
    description: "Record a stock count at the shop"
  },
  "inv.adjust.request": {
    label: "Request stock adjustment",
    description: "Create and submit stock adjustments"
  },
  "inv.adjust.approve": {
    label: "Approve stock adjustment",
    description: "Approve or reject stock adjustments"
  },
  "inv.writeoff.request": {
    label: "Request stock write-off",
    description: "Request a write-off"
  },
  "inv.writeoff.witness": {
    label: "Witness stock write-off",
    description: "Witness a write-off"
  },
  "inv.writeoff.approve": {
    label: "Approve stock write-off",
    description: "Approve or reject a write-off"
  },
  "inv.recipe.manage": {
    label: "Manage BOM repack recipes",
    description: "Define and retire repack recipes"
  },
  "inv.repack.execute": {
    label: "Execute stock repack",
    description: "Plan and execute a repack"
  },
  "inv.repack.reverse": {
    label: "Reverse stock repack",
    description: "Reverse a repack"
  },
  "inv.transfer.issue": {
    label: "Issue stock transfer",
    description: "Issue, dispatch and cancel stock transfers"
  },
  "shop.transfer.receive": {
    label: "Receive shop transfer",
    description: "Receive a stock transfer at the shop"
  },
  "inv.opening.prepare": {
    label: "Prepare opening stock",
    description: "Prepare an opening stock balance"
  },
  "inv.opening.sign": {
    label: "Sign opening stock",
    description: "Sign an opening stock balance for the entity"
  },
  "inv.opening.countersign": {
    label: "Countersign opening stock",
    description: "Countersign an opening stock balance for the Federation"
  },
  "inv.policy.set": {
    label: "Set inventory policy",
    description: "Set variance thresholds, loss tolerances and expiry windows"
  },
  "whs.pick": {
    label: "Pick warehouse orders",
    description: "Pick delivery notes at the warehouse"
  },
  "inv.reorder.manage": {
    label: "Manage reorder thresholds",
    description: "Maintain reorder levels, reorder quantities and maximum quantities"
  },
  "inv.stock.reprice": {
    label: "Reprice stock batch",
    description: "Reprice stock lot unit cost and adjust valuation"
  },
  "inv.expiry.scan": {
    label: "Scan batch expiries",
    description: "Trigger evaluation and scan of near-expiry and slow-moving stock alerts"
  },
  "rpt.report.run": {
    label: "Run analytical reports",
    description: "View reports and the dashboard"
  },
  "rpt.export.run": {
    label: "Export report data",
    description: "Export a report as CSV or print it to PDF"
  },
  "rpt.target.view": {
    label: "View targets",
    description: "View shop sales targets and actual performance comparison"
  },
  "rpt.target.manage": {
    label: "Manage targets",
    description: "Define and update shop sales targets"
  },
  "rpt.profit_center.view": {
    label: "View profit centers",
    description: "View profit center dimensions and month-end P&L reports"
  },
  "rpt.profit_center.manage": {
    label: "Manage profit centers",
    description: "Define and maintain profit center reporting dimensions"
  },
  "rpt.asset.view": {
    label: "View assets",
    description: "View fixed asset registers and branch asset distributions"
  },
  "rpt.asset.manage": {
    label: "Manage assets",
    description: "Register and maintain society fixed assets"
  },
  "pos.receipt.view": {
    label: "View sales receipts",
    description: "See the receipts and till sessions of the shops"
  },
  "pos.receipt.margin": {
    label: "View receipt margin",
    description: "View cost, profit and margin on sales receipts"
  },
  "cus.customer.view": {
    label: "Customer view",
    description: "See the society's customers, their accounts and statements"
  },
  "cus.customer.register": {
    label: "Customer register",
    description: "Register a customer"
  },
  "cus.customer.manage": {
    label: "Customer manage",
    description: "Amend, re-phone and deactivate a customer"
  },
  "cus.account.manage": {
    label: "Account manage",
    description: "Open a customer's credit account"
  },
  "cus.payment.record": {
    label: "Payment record",
    description: "Record a customer's repayment"
  },
  "cus.account.adjust": {
    label: "Account adjust",
    description: "Ask for an adjustment of a customer's balance"
  },
  "cus.account.adjust_approve": {
    label: "Account adjust approve",
    description: "Approve an adjustment another person asked for"
  },
  "cus.payment.reverse": {
    label: "Payment reverse",
    description: "Reverse a customer's repayment recorded in error"
  },
  "cus.privacy.record": {
    label: "Privacy record",
    description: "Record and read customers' requests for their data, a correction or erasure"
  },
  "cus.privacy.fulfil": {
    label: "Privacy fulfil",
    description: "Answer customers' data requests as the society's responsible officer"
  },
  "int.journal.read": {
    label: "Journal read",
    description: "See the entity's journal exports, their lines and their reconciliation"
  },
  "int.journal.export": {
    label: "Journal export",
    description: "Generate a journal export for the accounting package and download it"
  },
  "int.notify.view": {
    label: "Notify view",
    description: "See the notification templates, rules and delivery log"
  },
  "int.notify.manage": {
    label: "Notify manage",
    description: "Activate and retire the federation's notification rules"
  },
  "int.employee.manage": {
    label: "Employee manage",
    description: "Register and maintain employee profiles for attendance and payroll"
  },
  "int.attendance.import": {
    label: "Attendance import",
    description: "Import biometric attendance records from fingerprint machines"
  },
  "int.overtime.approve": {
    label: "Overtime approve",
    description: "Approve calculated overtime for payroll contribution"
  },
  "int.payroll.view": {
    label: "Payroll view",
    description: "View employee profiles, attendance records, overtime calculations and payroll contributions"
  },
  "int.delivery.track": {
    label: "Delivery track",
    description: "Record delivery vehicle and mobile device GPS location updates"
  },
  "int.delivery.manage": {
    label: "Delivery manage",
    description: "Plan delivery trips, record drops, and update delivery status"
  },
  "int.delivery.view": {
    label: "Delivery view",
    description: "View live vehicle locations, route history, and delivery reports"
  },
  "sync.quarantine.resolve": {
    label: "Resolve sync quarantine",
    description: "Resolve a till event the sync gateway quarantined, as repaired or discarded"
  },
  "sync.release.manage": {
    label: "Manage till releases",
    description: "Publish, pause, resume till application releases and assign rollout rings"
  },
  "pos.session.manage": {
    label: "Manage till session",
    description: "Move the business date backward on a till while no session is open"
  },
  "pos.receipt.issue": {
    label: "Issue sales receipt",
    description: "Issue a sale receipt"
  },
  "pos.receipt.void": {
    label: "Void sales receipt",
    description: "Void an entire receipt"
  },
  "pos.refund.same_session": {
    label: "Same-session refund",
    description: "Issue a refund for a receipt from the current session"
  },
  "pos.negative_stock.acknowledge": {
    label: "Acknowledge negative stock",
    description: "Acknowledge a negative stock warning"
  },
  "pos.mrp.pick": {
    label: "Pick multi-MRP batch",
    description: "Pick MRP for a SKU with multiple batches"
  },
};

/**
 * Returns human-readable action label, full description, prefix, and rest code for a permission.
 */
export function getPermissionInfo(code: string): { label: string; description: string; prefix: string; action: string } {
  const parts = code.split(".");
  const prefix = parts[0] || "other";
  const action = parts.slice(1).join(".");

  const canonical = PERMISSION_CATALOGUE[code];
  if (canonical) {
    return {
      label: canonical.label,
      description: canonical.description,
      prefix,
      action
    };
  }

  // Fallback for custom or newly added permissions:
  const fallbackWords = action.replace(/[_.]/g, " ");
  const fallbackLabel = fallbackWords ? fallbackWords.charAt(0).toUpperCase() + fallbackWords.slice(1) : code;

  return {
    label: fallbackLabel,
    description: fallbackLabel,
    prefix,
    action
  };
}

/** Standard responsibilities for seeded roles and templates. */
export const ROLE_DUTIES: Record<string, string> = {
  "Demo: accounts": "Manages trading credit limits, generates customer invoices & statements, issues credit/debit notes, and records payments.",
  "Accounts": "Manages trading credit limits, generates customer invoices & statements, issues credit/debit notes, and records payments.",
  "Demo: administration": "Manages system user accounts & credentials, entity registration & status, role assignments, and external regulatory grants.",
  "Entity Administrator": "Full administrative governance over users, roles, trading relationships, locations, and pricing policies.",
  "Demo: catalogue steward": "Governs national and local SKU catalogue items, approvals, barcode bindings, and merchandise tagging.",
  "Federation Catalogue Steward": "Governs national SKU catalogue items, promotion of local lines, barcodes, and governed tags.",
  "Demo: pricing and relationships": "Sets trade price lists, customer discount rules, gazetted price ceilings, and trading agreements.",
  "Demo: stores and dispatch": "Oversees warehouse stock movements, dispatch notes, deliveries, physical stock counts, and write-offs.",
  "Demo: sales": "Processes customer sales orders, manages quotation terms, and coordinates order fulfillment dispatch.",
  "Demo: buying, selling and prices": "Coordinates procurement orders, supplier price lists, and trade relationship terms.",
  "Demo: stores and receiving": "Handles inventory receipts (GRNs), stock movements, transfers, counts, and write-offs.",
  "Demo: society buyer": "Issues purchase orders and coordinates inventory purchasing for the cooperative society.",
  "Demo: society manager": "Oversees society operations, signs opening stock, authorizes transfers, and monitors reports.",
  "Demo: shop staff": "Processes point-of-sale customer sales, issues till receipts, confirms incoming shop deliveries, and conducts stock counts.",
  "Demo: society office": "Maintains society operational records, member credit ledgers, and administrative documentation.",
  "Federation Viewer": "Read-only oversight across federation reports, targets, assets, catalogue, and trading entities.",
  "Regulator": "Timeboxed read-only regulatory inspection of entities, locations, catalogue, and inventory.",
  "Trading Buyer": "Authorizes purchase orders, reviews prices, and tracks incoming trade deliveries.",
  "Trading Seller": "Manages wholesale sales orders, dispatches delivery notes, and issues customer invoices.",
  "Cashier": "Processes till receipts, voids sales when authorized, and manages shift till sessions.",
  "Shop Supervisor": "Supervises shop tills, handles return authorizations, and monitors shop-floor inventory.",
  "Shop-in-charge": "Responsible for shop opening stock, daily till reconciliation, and shop goods receipts.",
  "Shop Manager": "Overall manager for retail shop operations, staff assignments, and stock replenishments.",
  "Shop Assistant": "Assists with retail till operations and customer merchandise handling.",
  "Marketing Manager": "Develops pricing discount rules, campaign promotions, and merchandise presentations.",
  "Stock Keeper": "Maintains warehouse inventory lots, stock movements, picking, and periodic stock-taking."
};

/**
 * Returns a concise summary of what this role does, localized via useT.
 */
export function getRoleDuties(
  roleNameEn: string,
  permissions: Array<{ permissionCode: string }>,
  t?: (id: string, defaultMessage?: string) => { text: string }
): string {
  const lower = roleNameEn.toLowerCase();
  let key: string | null = null;
  if (lower.includes("account")) key = "accounts";
  else if (lower.includes("admin")) key = "administration";
  else if (lower.includes("catalogue")) key = "catalogue";
  else if (lower.includes("pricing") || lower.includes("price")) key = "pricing";
  else if (lower.includes("stores") || lower.includes("receiving") || lower.includes("dispatch")) key = "stores";
  else if (lower.includes("sales") || lower.includes("seller")) key = "sales";
  else if (lower.includes("buyer")) key = "buyer";
  else if (lower.includes("manager")) key = "manager";
  else if (lower.includes("shop") || lower.includes("cashier")) key = "shop_staff";
  else if (lower.includes("office")) key = "office";
  else if (lower.includes("viewer")) key = "viewer";
  else if (lower.includes("regulator")) key = "regulator";

  if (t && key) {
    const localized = t(`party.roles.duty.${key}`, ROLE_DUTIES[key] || ROLE_DUTIES[roleNameEn]);
    if (localized.text) {
      return localized.text;
    }
  }

  if (ROLE_DUTIES[roleNameEn]) {
    return ROLE_DUTIES[roleNameEn];
  }
  // Case-insensitive lookup
  for (const [name, duties] of Object.entries(ROLE_DUTIES)) {
    if (roleNameEn.toLowerCase().includes(name.toLowerCase())) {
      return duties;
    }
  }

  // Dynamic fallback for custom roles:
  if (permissions.length === 0) {
    return t ? t("party.roles.no_permissions", "This role grants no permission.").text : "This role grants no permission.";
  }

  const sampleLabels = permissions.slice(0, 3).map((p) => {
    const info = getPermissionInfo(p.permissionCode);
    return t ? t(`party.roles.perm.${p.permissionCode}`, info.label).text : info.label;
  });
  if (permissions.length <= 3) {
    return `${sampleLabels.join(", ")}.`;
  }
  return `${sampleLabels.join(", ")}, +${permissions.length - 3}.`;
}
