-- Workspace stochastic identity (PLAN-SEED-IDENTITY §5.2 / §12.2).
-- seed_entity_id is the HDR generator's Entity axis: assigned from a monotonic
-- sequence (never reused), or provided at creation (UNIQUE enforces global
-- uniqueness among live workspaces). Range bound by the HDR 8-decimal-digit
-- ID budget; must match the SeedEntityId Iron type in common.
CREATE SEQUENCE workspace_seed_entity_id_seq START 1 MAXVALUE 99999999;

ALTER TABLE workspaces
  ADD COLUMN seed_entity_id BIGINT NOT NULL UNIQUE
    DEFAULT nextval('workspace_seed_entity_id_seq')
    CHECK (seed_entity_id BETWEEN 1 AND 99999999);

-- Tie the sequence to the column so TRUNCATE ... RESTART IDENTITY resets it
-- (deterministic fresh-store base, PLAN-SEED-IDENTITY §5.5) and it drops with
-- the table.
ALTER SEQUENCE workspace_seed_entity_id_seq OWNED BY workspaces.seed_entity_id;
