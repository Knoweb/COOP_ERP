package lk.coopfed.archfixtures.kernel;

import lk.coopfed.archfixtures.m1party.MasterReachesTransactions;

/** Violates R5: kernel code depending on a module. */
public class LeakyKernel {

    private final MasterReachesTransactions module = new MasterReachesTransactions();

    public MasterReachesTransactions module() {
        return module;
    }
}
