package lk.coopfed.knoweb.m5inventory.api;

/**
 * The kinds of stock movement (doc 18 part B; 25A section 3), with the two facts the ledger needs
 * about each: which way it may move a lot, and whether it carries a cost of its own.
 *
 * <ul>
 *   <li>An <b>intake at cost</b> (RECEIPT, OPENING_BALANCE, REPACK_PRODUCE) brings stock at the
 *       cost the caller gives and re-averages the entity's cost (doc 25 section 3.3).
 *   <li>TRANSFER_IN brings stock at the cost the source lot had (the caller gives it) and leaves
 *       the entity average alone: the stock stayed inside the entity.
 *   <li>Every other movement is costed at the entity average of that moment and changes the
 *       entity's quantity only.
 * </ul>
 */
public enum MovementType {
    RECEIPT(Direction.IN, true),
    OPENING_BALANCE(Direction.IN, true),
    REPACK_PRODUCE(Direction.IN, true),
    TRANSFER_IN(Direction.IN, true),
    SALE_REVERSAL(Direction.IN, false),
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

    /** The caller gives the unit cost (an intake, or a transfer in at the source lot's cost). */
    public boolean carriesItsOwnCost() {
        return carriesItsOwnCost;
    }

    /** An intake that re-averages the entity's cost: every intake at a cost but a transfer in. */
    public boolean reaverages() {
        return carriesItsOwnCost && this != TRANSFER_IN;
    }
}
