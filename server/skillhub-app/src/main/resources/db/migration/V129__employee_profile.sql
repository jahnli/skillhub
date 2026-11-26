ALTER TABLE user_account
    ADD COLUMN IF NOT EXISTS employee_company TEXT,
    ADD COLUMN IF NOT EXISTS employee_department TEXT,
    ADD COLUMN IF NOT EXISTS employee_number TEXT,
    ADD COLUMN IF NOT EXISTS employee_job_title TEXT,
    ADD COLUMN IF NOT EXISTS employee_phone TEXT;
