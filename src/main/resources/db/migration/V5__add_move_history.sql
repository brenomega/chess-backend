-- EAP 2.7.6 / 2.12.3: manter o histórico UCI da partida para permitir
-- reconstruir o estado após reinício ou reconexão.
ALTER TABLE game ADD COLUMN move_history TEXT NOT NULL DEFAULT '';
