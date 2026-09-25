package lk.coopfed.knoweb.m1party.internal.relationship;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RelationshipRepository extends JpaRepository<Relationship, UUID> {

    /**
     * The ACTIVE rows of a pair whose effective range meets [from, to], both days included, as
     * the A-I3 exclusion constraint compares them. {@code to} is never null: an open-ended range
     * is passed as {@link RelationshipRules#OPEN_END}. The guards ask this first so that the
     * refusal can name the row in the way; the constraint is the backstop for a race.
     */
    @Query(
            """
            select r from Relationship r
             where r.sellerEntityId = :seller
               and r.buyerEntityId = :buyer
               and r.status = 'ACTIVE'
               and r.id <> :exclude
               and r.effectiveFrom <= :to
               and (r.effectiveTo is null or r.effectiveTo >= :from)
             order by r.effectiveFrom
            """)
    List<Relationship> activeOverlapping(
            @Param("seller") UUID seller,
            @Param("buyer") UUID buyer,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("exclude") UUID exclude);
}
