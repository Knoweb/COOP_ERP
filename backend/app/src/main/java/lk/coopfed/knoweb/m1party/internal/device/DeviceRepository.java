package lk.coopfed.knoweb.m1party.internal.device;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

    /** Only the devices the caller may see: a serial of another entity is found by the unique key. */
    boolean existsByHardwareSerial(String hardwareSerial);

    /** The device holding a position, active or suspended (one at most: one_device_per_position). */
    Optional<Device> findByCurrentTillPositionId(UUID tillPositionId);
}
