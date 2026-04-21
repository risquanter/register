CREATE TABLE workspaces (
  id           TEXT PRIMARY KEY,
  key_hash     TEXT NOT NULL UNIQUE,
  created_at   TIMESTAMPTZ NOT NULL,
  last_access  TIMESTAMPTZ NOT NULL,
  ttl          INTERVAL NOT NULL,
  idle_timeout INTERVAL NOT NULL
);

CREATE TABLE workspace_trees (
  workspace_id TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
  tree_id      TEXT NOT NULL,
  added_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (workspace_id, tree_id)
);
