package lk.coopfed.knoweb.m5inventory.api;

/**
 * The kinds of stock movement (doc 18 part B; 25A section 3), with the two facts the ledger needs
 * about each: which way it may move a lot, and whether it carries a cost of its own.
 *
 * <ul>
 *   <li>An <b>intake at its own cost</b> brings stock at the cost the caller gives and re-averages
 *       the entity's cost (doc 25 section 3.3): RECEIPT, OPENING_BALANCE, REPACK_PRODUCE;
 *       TRANSFER_IN at the cost its TRANSFER_OUT left with, so the value that left comes back
 *       exactly and an intake during transit is averaged against the right base (wave 2, M5-06);
 *       SALE_REVERSAL at the original SALE's cost, so a void undoes the sale's cost exactly (D10).
 *   <li>Every other movement is costed at the entity average of that moment and changes the
 *       entity's quantity only, unless the caller gives an out movement a cost of its own (the
 *       repack reversal's REPACK_CONSUME at the repack's output cost): then that value leaves.
 * </ul>
 */
public enum MovementType {
    RECEIPT(Direction.IN, true),
    OPENING_BALANCE(Direction.IN, true),
    REPACK_PRODUCE(Direction.IN, true),
    TRANSFER_IN(Direction.IN, true),
    SALE_REVERSAL(Direction.IN, true),
    SALE(Direction.OUT, false),
    TRANSFER_OUT(Direction.OUT, false),
    REPACK_CONSUME(Direction.OUT, false),
    WRITE_OFF(Direction.OUT, false),
    RETURN_TO_SELLER(Direction.OUT, false),
    GRN_REVERSAL(Direction.OUT, false),
    COUNT_ADJUST(Direction.EITHER, false);

    /** Which sign a movement of the type may have. */
    public enum Direction {
        IN,
        OUT,
        EITHER
    }

    private final Direction direction;
    private final boolean carriesItsOwnCost;

    MovementType(Direction direction, boolean carriesItsOwnCost) {
        this.direction = direction;
        this.carriesItsOwnCost = carriesItsOwnCost;
    }

    public Direction direction() {
        return direction;
    }

    /**
     * The caller gives the unit cost and the intake re-averages the entity's cost with it: every
     * intake at a cost, a transfer in and a sale reversal included.
     */
    public boolean carriesItsOwnCost() {
        return carriesItsOwnCost;
    }
}
