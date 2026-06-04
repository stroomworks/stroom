--
-- Create the updatable_temporal_store table
--
CREATE TABLE IF NOT EXISTS updatable_temporal_store (
  map_name            varchar(255) NOT NULL,
  key_                varchar(255) NOT NULL,
  effective_time      bigint NOT NULL,
  value_              longtext,
  PRIMARY KEY (map_name, key_, effective_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
