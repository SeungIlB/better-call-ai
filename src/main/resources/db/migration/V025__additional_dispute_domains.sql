ALTER TABLE casework.cases DROP CONSTRAINT cases_dispute_domain_check;
ALTER TABLE casework.cases ADD CONSTRAINT cases_dispute_domain_check
    CHECK (dispute_domain IN ('housing_lease', 'vehicle_accident', 'assault', 'labor', 'consumer', 'commercial', 'family', 'inheritance', 'defamation', 'personal_injury'));
