CREATE UNIQUE INDEX game_private_waiting_entry_code_uq
    ON game (entry_code)
    WHERE visibility = 'PRIVATE' AND status = 'WAITING';
