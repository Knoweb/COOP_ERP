package lk.coopfed.knoweb.m1party.internal.device;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

    /** Only the devices the caller may see: a serial of another entity is found by the unique key. */
    boolean existsByHardwareSerial(String hardwareSerial);

    /** The device holding a position, active or suspended (one at most: one_device_per_position). */
    Optional<Device> findByCurrentTillPositionId(UUID tillPositionId);

    /**
     * The device, locked for the rest of the transaction: two assignments of one device run one
     * after the other, and the second sees the position the first gave it (the review of
     * M1-06). Empty when the caller's row-level security does not see the row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Device d where d.id = :id")
    Optional<Device> findByIdForUpdate(@Param("id") UUID id);

    /** The holder of a position, locked, so that it cannot be reassigned while it gives the position up. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Device d where d.currentTillPositionId = :tillPositionId")
    Optional<Device> findByCurrentTillPositionIdForUpdate(@Param("tillPositionId") UUID tillPositionId);
}
