-- PRC-11: close a superseded version's lines at successor.apply_from - 1 day.
-- Draft lines remain editable. All other changes to published/superseded
-- lines remain forbidden by this trigger.

CREATE OR REPLACE FUNCTION pricing.line_of_a_draft() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    list_status text;
BEGIN
    SELECT l.status INTO list_status
      FROM pricing.price_list l
     WHERE l.price_list_id = coalesce(NEW.price_list_id, OLD.price_list_id);

    IF list_status = 'DRAFT' THEN
        RETURN coalesce(NEW, OLD);
    END IF;

    -- Only the effective_to closure of a superseded line is permitted.
    -- A published successor must already exist, and the date must equal
    -- exactly the day before that successor starts.
    IF TG_OP = 'UPDATE'
       AND list_status = 'SUPERSEDED'
       AND OLD.effective_to IS NULL
       AND NEW.effective_to IS NOT NULL
       AND NEW.effective_to >= OLD.effective_from
       AND (to_jsonb(NEW) - 'effective_to')
           = (to_jsonb(OLD) - 'effective_to')
       AND EXISTS (
           SELECT 1
             FROM pricing.price_list previous
             JOIN pricing.price_list successor
               ON successor.root_price_list_id = previous.root_price_list_id
              AND successor.version > previous.version
              AND successor.status = 'PUBLISHED'
            WHERE previous.price_list_id = OLD.price_list_id
              AND previous.status = 'SUPERSEDED'
              AND successor.apply_from = NEW.effective_to + 1
       )
    THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'price list % is not a draft',
        coalesce(NEW.price_list_id, OLD.price_list_id)
        USING ERRCODE = 'check_violation';
END;
$$;

-- No permission to change price, SKU, UOM, tier, or line identity.
GRANT UPDATE (effective_to) ON pricing.price_list_line TO app_rw;
