-- Drop existing user if any (ignore error if doesn't exist)
DROP USER IF EXISTS 'snowflake'@'%';

-- Create for all access patterns
CREATE USER 'snowflake'@'%' IDENTIFIED BY 'password';

-- Grant privileges
GRANT ALL PRIVILEGES ON snowflake_db.* TO 'snowflake'@'%';

FLUSH PRIVILEGES;