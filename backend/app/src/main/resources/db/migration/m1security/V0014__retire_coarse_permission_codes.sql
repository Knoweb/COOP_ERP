-- CR-21A-1 item 1, accepted as revised on 27 September 2026: the permission catalogue follows
-- 21A section 3.3 verb by verb. Four coarse codes that no command or query ever asked for are
-- retired: gov.entity.manage, prt.relationship.manage, prt.location.manage, sys.device.manage.
-- Their verbs are gov.entity.register/.suspend, prt.relationship.open/.activate/.amend/.suspend,
-- prt.location.register/.activate/.primary and sys.device.enrol/.suspend, all in the catalogue
-- already.
--
-- The seed loader only inserts (21A section 3.3, "upsert by key"), so a code taken out of
-- seed/m1party/permissions.yaml would stay in every database that loaded it before. This removes
-- it, with the rows that name it: a role's grant of it (templates and entity roles alike) and a
-- separation-of-duties pair over it. On a new database it removes nothing, because the tables
-- are filled by the loader after the migrations have run.
--
-- It runs as the migrator, a member of app_seed, which the seed_reference policies of V0006
-- admit under FORCE ROW LEVEL SECURITY. No grant of app_rw changes.

DO $retire$
DECLARE
    retired constant text[] := ARRAY[
        'gov.entity.manage',
        'prt.relationship.manage',
        'prt.location.manage',
        'sys.device.manage'
    ];
    removed bigint := 0;
    n bigint;
BEGIN
    DELETE FROM security.role_permission WHERE permission_code = ANY (retired);
    GET DIAGNOSTICS n = ROW_COUNT;
    removed := removed + n;

    DELETE FROM security.sod_pair WHERE permission_a = ANY (retired) OR permission_b = ANY (retired);
    GET DIAGNOSTICS n = ROW_COUNT;
    removed := removed + n;

    DELETE FROM security.permission WHERE permission_code = ANY (retired);
    GET DIAGNOSTICS n = ROW_COUNT;
    removed := removed + n;

    -- A new catalogue version invalidates every cached permission set (19A section 3), as the
    -- seed loader does when it inserts; only when something was removed.
    IF removed > 0 THEN
        INSERT INTO security.permission_catalogue_version (rv, published_at)
        VALUES (COALESCE((SELECT max(rv) FROM security.permission_catalogue_version), 0) + 1, now());
    END IF;
END
$retire$;
