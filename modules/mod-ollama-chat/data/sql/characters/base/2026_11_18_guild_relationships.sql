CREATE TABLE IF NOT EXISTS `ollama_chat_sessions` (
  `player_guid` BIGINT UNSIGNED NOT NULL,
  `id` INT UNSIGNED NOT NULL,
  `guild_id` INT UNSIGNED NOT NULL,
  `session_type` VARCHAR(24) NOT NULL DEFAULT 'guild_relationship',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_activity` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `ended_at` DATETIME DEFAULT NULL,
  `status` ENUM('active','ended','consolidated') NOT NULL DEFAULT 'active',
  `bot_initiated` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `retry_after` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`player_guid`,`id`),
  KEY `pending_memory` (`status`,`retry_after`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `ollama_chat_participants` (
  `player_guid` BIGINT UNSIGNED NOT NULL,
  `session_id` INT UNSIGNED NOT NULL,
  `bot_guid` BIGINT UNSIGNED NOT NULL,
  `name` VARCHAR(32) NOT NULL,
  `joined_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`player_guid`,`session_id`,`bot_guid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `ollama_chat_session_history` (
  `player_guid` BIGINT UNSIGNED NOT NULL,
  `session_id` INT UNSIGNED NOT NULL,
  `player_name` VARCHAR(32) NOT NULL,
  `transcript` TEXT NOT NULL,
  PRIMARY KEY (`player_guid`,`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `ollama_bot_memories` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `player_guid` BIGINT UNSIGNED NOT NULL,
  `bot_guid` BIGINT UNSIGNED NOT NULL,
  `memory_type` VARCHAR(24) NOT NULL,
  `memory` VARCHAR(320) NOT NULL,
  `memory_hash` VARBINARY(64) NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `relationship_memory` (`player_guid`,`bot_guid`,`memory_hash`),
  KEY `recent_memories` (`player_guid`,`bot_guid`,`updated_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
