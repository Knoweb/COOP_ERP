package lk.coopfed.knoweb.demo;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What each demo shop keeps on its shelves (DEMO-02b): twenty-odd everyday packed items, in
 * quantities that last the eight weeks of the till history (DemoTillHistory sells one or two
 * baskets of one to five items, one to three of each, three days a week, about ten of an item over
 * the eight weeks). The Kuliyapitiya shops get theirs from the society's stores by transfer; the
 * Pannala and Point Pedro societies have no stores of their own (their distributor delivers to the
 * shop), so each of their shops counted its opening stock on the day it went live.
 *
 * <p>Only packed items with a barcode: loose goods are weighed at the stores, and the till history
 * sells by barcode. Every name is an English name of {@code demo/catalogue.psv}.
 */
final class DemoShopStock {

    private DemoShopStock() {}

    /** An item of the catalogue by its English name and how many of it the shop receives. */
    record Shelf(String nameEn, int qty) {}

    /** The town shop's first transfer, at the set-up: the society's main shop, the widest range. */
    static final List<Shelf> TOWN_SHOP = List.of(
            new Shelf("Samba rice 5 kg", 26),
            new Shelf("Nadu rice 5 kg", 30),
            new Shelf("Keeri samba rice 1 kg", 26),
            new Shelf("Red dhal 1 kg", 30),
            new Shelf("Mysore dhal 500 g", 26),
            new Shelf("White sugar 1 kg", 36),
            new Shelf("Wheat flour 1 kg", 30),
            new Shelf("Coconut oil 500 ml", 26),
            new Shelf("Full cream milk powder 400 g", 26),
            new Shelf("Ceylon tea 200 g", 26),
            new Shelf("Tea bags, 50", 26),
            new Shelf("Laundry soap bar", 48),
            new Shelf("Bath soap 100 g", 48),
            new Shelf("Washing powder 1 kg", 26),
            new Shelf("Toothpaste 120 g", 26),
            new Shelf("Salt 1 kg", 30),
            new Shelf("Chilli powder 100 g", 26),
            new Shelf("Curry powder 100 g", 26),
            new Shelf("Soya meat 90 g", 30),
            new Shelf("Canned mackerel 425 g", 26),
            new Shelf("Cream crackers 190 g", 26),
            new Shelf("Noodles 400 g", 26),
            new Shelf("Matches, 10 boxes", 30));

    /** The Hettipola shop's first transfer, a month later: a smaller shop, four weeks of selling. */
    static final List<Shelf> HETTIPOLA_SHOP = List.of(
            new Shelf("Nadu rice 5 kg", 16),
            new Shelf("Red dhal 1 kg", 16),
            new Shelf("White sugar 1 kg", 20),
            new Shelf("Wheat flour 1 kg", 16),
            new Shelf("Coconut oil 500 ml", 12),
            new Shelf("Full cream milk powder 400 g", 12),
            new Shelf("Ceylon tea 200 g", 16),
            new Shelf("Laundry soap bar", 30),
            new Shelf("Bath soap 100 g", 24),
            new Shelf("Carbolic soap 110 g", 20),
            new Shelf("Salt 1 kg", 16),
            new Shelf("Soya meat 90 g", 12),
            new Shelf("Canned mackerel 425 g", 12),
            new Shelf("Cream crackers 190 g", 12),
            new Shelf("Matches, 10 boxes", 16),
            new Shelf("Kerosene lamp wick", 20));

    /** The Pannala shop's opening stock (M102), counted at the set-up. */
    static final List<Shelf> PANNALA_SHOP = List.of(
            new Shelf("Samba rice 5 kg", 20),
            new Shelf("Nadu rice 5 kg", 24),
            new Shelf("Red raw rice 5 kg", 16),
            new Shelf("Red dhal 1 kg", 30),
            new Shelf("White sugar 1 kg", 36),
            new Shelf("Brown sugar 1 kg", 16),
            new Shelf("Wheat flour 1 kg", 30),
            new Shelf("Rice flour 1 kg", 16),
            new Shelf("Coconut oil 1 l", 16),
            new Shelf("Vegetable oil 1 l", 16),
            new Shelf("Full cream milk powder 1 kg", 12),
            new Shelf("Ceylon tea 400 g", 16),
            new Shelf("Laundry soap bar", 48),
            new Shelf("Bath soap 100 g", 36),
            new Shelf("Dishwash liquid 500 ml", 16),
            new Shelf("Salt 1 kg", 30),
            new Shelf("Turmeric powder 50 g", 24),
            new Shelf("Chickpeas 500 g", 20),
            new Shelf("Green gram 500 g", 20),
            new Shelf("Noodles 400 g", 24));

    /** The Point Pedro shop's opening stock (M103), counted at the set-up. */
    static final List<Shelf> POINT_PEDRO_SHOP = List.of(
            new Shelf("Nadu rice 5 kg", 24),
            new Shelf("Keeri samba rice 1 kg", 30),
            new Shelf("Red dhal 1 kg", 30),
            new Shelf("Mysore dhal 500 g", 30),
            new Shelf("White sugar 1 kg", 36),
            new Shelf("Wheat flour 1 kg", 30),
            new Shelf("Kurakkan flour 500 g", 20),
            new Shelf("Coconut oil 500 ml", 24),
            new Shelf("Full cream milk powder 400 g", 24),
            new Shelf("Tea bags, 50", 16),
            new Shelf("Ceylon tea 200 g", 24),
            new Shelf("Carbolic soap 110 g", 30),
            new Shelf("Bath soap 100 g", 30),
            new Shelf("Washing powder 1 kg", 16),
            new Shelf("Toothpaste 120 g", 20),
            new Shelf("Chilli powder 100 g", 30),
            new Shelf("Curry powder 100 g", 30),
            new Shelf("Canned mackerel 425 g", 24),
            new Shelf("Cream crackers 190 g", 24),
            new Shelf("Matches, 10 boxes", 30));

    /** Every shop's first stock, by shop. */
    static final Map<UUID, List<Shelf>> BY_SHOP = Map.of(
            DemoCast.M101_TOWN_SHOP, TOWN_SHOP,
            DemoCast.M101_HETTIPOLA_SHOP, HETTIPOLA_SHOP,
            DemoCast.M102_SHOP, PANNALA_SHOP,
            DemoCast.M103_SHOP, POINT_PEDRO_SHOP);
}
