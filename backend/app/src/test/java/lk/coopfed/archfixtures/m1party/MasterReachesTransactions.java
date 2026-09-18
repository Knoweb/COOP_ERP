package lk.coopfed.archfixtures.m1party;

import lk.coopfed.archfixtures.m4trading.TradingThing;

/** Violates R2/R3: master data depending on the transactions layer. */
public class MasterReachesTransactions {

    private final TradingThing thing = new TradingThing();

    public TradingThing thing() {
        return thing;
    }
}
