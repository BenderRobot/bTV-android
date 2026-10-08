-- bTV: watch progress, history, favourites and track choices shared between
-- devices signed in to the same IPTV account.
--
-- Security model (the app and this repository are public):
--   * the table is closed to the public API (RLS on, no policy, privileges
--     revoked): nobody can list or read it directly;
--   * devices only call sync_push / sync_pull, which require the account's
--     sync key: a SHA-256 the app derives from the IPTV server, username AND
--     password - impossible to guess, and the password never leaves the device;
--   * rows hold no credentials, only what is needed to resume and list content.
--
-- Idempotent: safe to run again (SQL editor or the GitHub integration).

create sequence if not exists public.sync_items_seq;

create table if not exists public.sync_items (
    account_key text   not null,
    kind        text   not null,
    item_id     text   not null,
    payload     jsonb  not null default '{}'::jsonb,
    deleted     boolean not null default false,
    -- Device time of the change (ms): the most recent change wins.
    updated_at  bigint not null,
    -- Server order of changes: devices pull "everything after seq N".
    seq         bigint not null default nextval('public.sync_items_seq'),
    primary key (account_key, kind, item_id),
    constraint sync_items_key_format check (account_key ~ '^[0-9a-f]{64}$'),
    constraint sync_items_kind check (kind in ('progress', 'history', 'favorite', 'track'))
);

create index if not exists sync_items_account_seq on public.sync_items (account_key, seq);

alter table public.sync_items enable row level security;
revoke all on table public.sync_items from anon, authenticated;
revoke all on sequence public.sync_items_seq from anon, authenticated;

-- Upserts a batch of changes; an older change never overwrites a newer one.
create or replace function public.sync_push(p_key text, p_items jsonb)
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
    applied integer;
begin
    if p_key is null or p_key !~ '^[0-9a-f]{64}$' then
        raise exception 'invalid sync key';
    end if;
    if p_items is null or jsonb_typeof(p_items) <> 'array' or jsonb_array_length(p_items) > 500 then
        raise exception 'invalid items';
    end if;

    insert into public.sync_items as s (account_key, kind, item_id, payload, deleted, updated_at, seq)
    select p_key, i.kind, i.id, coalesce(i.payload, '{}'::jsonb), coalesce(i.deleted, false), i."updatedAt",
           nextval('public.sync_items_seq')
    from jsonb_to_recordset(p_items) as i(kind text, id text, payload jsonb, deleted boolean, "updatedAt" bigint)
    where i.kind in ('progress', 'history', 'favorite', 'track')
      and i.id is not null and length(i.id) <= 300
      and i."updatedAt" is not null
      and pg_column_size(coalesce(i.payload, '{}'::jsonb)) < 16384
    on conflict (account_key, kind, item_id) do update
        set payload = excluded.payload,
            deleted = excluded.deleted,
            updated_at = excluded.updated_at,
            seq = excluded.seq
        where s.updated_at < excluded.updated_at;

    get diagnostics applied = row_count;
    return applied;
end;
$$;

-- Changes of one account after a given server position, oldest first.
create or replace function public.sync_pull(p_key text, p_since bigint default 0, p_limit integer default 1000)
returns table (kind text, item_id text, payload jsonb, deleted boolean, updated_at bigint, seq bigint)
language sql
stable
security definer
set search_path = public
as $$
    select s.kind, s.item_id, s.payload, s.deleted, s.updated_at, s.seq
    from public.sync_items s
    where p_key ~ '^[0-9a-f]{64}$'
      and s.account_key = p_key
      and s.seq > coalesce(p_since, 0)
    order by s.seq
    limit least(greatest(coalesce(p_limit, 1000), 1), 2000);
$$;

revoke all on function public.sync_push(text, jsonb) from public;
revoke all on function public.sync_pull(text, bigint, integer) from public;
grant execute on function public.sync_push(text, jsonb) to anon, authenticated;
grant execute on function public.sync_pull(text, bigint, integer) to anon, authenticated;
