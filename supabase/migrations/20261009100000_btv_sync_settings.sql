-- bTV: account settings shared between devices too (kind 'setting'):
-- language filter, hidden and pinned categories, parental PIN (salted hash,
-- never the PIN itself) and unlocked adult categories. Same table, same
-- functions, same security model as 20261008190000_btv_sync.sql.
--
-- Idempotent: safe to run again (SQL editor or the GitHub integration).

alter table public.sync_items drop constraint if exists sync_items_kind;
alter table public.sync_items
    add constraint sync_items_kind check (kind in ('progress', 'history', 'favorite', 'track', 'setting'));

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
    where i.kind in ('progress', 'history', 'favorite', 'track', 'setting')
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

revoke all on function public.sync_push(text, jsonb) from public;
grant execute on function public.sync_push(text, jsonb) to anon, authenticated;
