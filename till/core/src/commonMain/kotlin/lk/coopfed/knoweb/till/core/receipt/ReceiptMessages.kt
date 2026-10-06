package lk.coopfed.knoweb.till.core.receipt

import lk.coopfed.knoweb.till.core.snapshot.Language

/**
 * The fixed words on the paper in each language (doc 26 section 3.4: "trilingual fixed strings").
 * The Sinhala and Tamil wording is a first draft for the trial and wants a translator's review
 * before a pilot; it moves to the shared message catalogue with the till screens.
 */
data class ReceiptMessages(
    val receipt: String,
    val date: String,
    val cashier: String,
    val counter: String,
    val total: String,
    val cash: String,
    val change: String,
    val thanks: String,
    val reprint: String,
    val zReport: String,
    val businessDate: String,
    val opened: String,
    val closed: String,
    val receipts: String,
    val sales: String,
    val float: String,
    val expected: String,
    val counted: String,
    val variance: String,
    /** "{n}" stands for the count. */
    val refusedFacts: String,
) {
    companion object {
        private val EN = ReceiptMessages(
            receipt = "Receipt", date = "Date", cashier = "Cashier", counter = "Counter", total = "Total",
            cash = "Cash", change = "Change", thanks = "Thank you", reprint = "REPRINT", zReport = "Z REPORT",
            businessDate = "Business date", opened = "Opened", closed = "Closed", receipts = "Receipts",
            sales = "Sales", float = "Float", expected = "Expected cash", counted = "Counted cash",
            variance = "Variance", refusedFacts = "{n} facts refused by central, see the office",
        )
        private val SI = ReceiptMessages(
            receipt = "රිසිට්පත", date = "දිනය", cashier = "මුදල් අයකැමි", counter = "කවුන්ටරය",
            total = "එකතුව", cash = "මුදල්", change = "ඉතිරි මුදල", thanks = "ස්තූතියි",
            reprint = "නැවත මුද්‍රණය", zReport = "Z වාර්තාව", businessDate = "ව්‍යාපාර දිනය",
            opened = "ආරම්භය", closed = "අවසානය", receipts = "රිසිට්පත්", sales = "විකුණුම්",
            float = "ආරම්භක මුදල", expected = "අපේක්ෂිත මුදල", counted = "ගණන් කළ මුදල",
            variance = "වෙනස", refusedFacts = "මධ්‍යස්ථානය ප්‍රතික්ෂේප කළ වාර්තා {n}ක්, කාර්යාලය අමතන්න",
        )
        private val TA = ReceiptMessages(
            receipt = "ரசீது", date = "தேதி", cashier = "காசாளர்", counter = "கவுண்டர்",
            total = "மொத்தம்", cash = "பணம்", change = "மீதி", thanks = "நன்றி",
            reprint = "மறுஅச்சு", zReport = "Z அறிக்கை", businessDate = "வணிக தேதி",
            opened = "தொடக்கம்", closed = "முடிவு", receipts = "ரசீதுகள்", sales = "விற்பனை",
            float = "தொடக்கப் பணம்", expected = "எதிர்பார்க்கும் பணம்", counted = "எண்ணிய பணம்",
            variance = "வேறுபாடு", refusedFacts = "மையம் நிராகரித்த {n} பதிவுகள், அலுவலகத்தை அணுகவும்",
        )

        fun of(language: Language): ReceiptMessages = when (language) {
            Language.EN -> EN
            Language.SI -> SI
            Language.TA -> TA
        }
    }
}
