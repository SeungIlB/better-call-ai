UPDATE casework.cases
SET dispute_domain = 'housing_lease'
WHERE dispute_domain IS NULL OR btrim(dispute_domain) = '';

ALTER TABLE casework.cases
    ALTER COLUMN dispute_domain SET DEFAULT 'housing_lease',
    ALTER COLUMN dispute_domain SET NOT NULL;

ALTER TABLE casework.cases
    ADD CONSTRAINT cases_dispute_domain_check
    CHECK (dispute_domain IN ('housing_lease', 'vehicle_accident', 'assault'));
