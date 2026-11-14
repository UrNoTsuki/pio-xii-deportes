create table if not exists public.balones (
    id uuid primary key default gen_random_uuid(),
    codigo text not null unique,
    disponible boolean not null default true,
    prestado_a text,
    fecha_prestamo timestamptz,
    constraint balones_estado_prestamo_valido check (
        (disponible and prestado_a is null and fecha_prestamo is null)
        or
        (not disponible and prestado_a is not null and fecha_prestamo is not null)
    )
);

begin;

alter table public.balones enable row level security;

create table if not exists public.teacher_calendar_events (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null default auth.uid()
        references auth.users(id) on delete cascade,
    teacher_name text not null check (length(btrim(teacher_name)) > 0),
    event_title text not null check (length(btrim(event_title)) > 0),
    event_date date not null,
    start_time time not null,
    end_time time not null,
    created_at timestamptz not null default now(),
    constraint teacher_calendar_event_time_valid check (end_time > start_time)
);

create table if not exists public.tarde_deportiva_matches (
    id uuid primary key default gen_random_uuid(),
    match_date date not null check (extract(dow from match_date) = 5),
    sport text not null check (length(btrim(sport)) > 0),
    start_time time not null,
    team_a text not null check (length(btrim(team_a)) > 0),
    team_b text not null check (length(btrim(team_b)) > 0),
    score_a integer not null check (score_a >= 0),
    score_b integer not null check (score_b >= 0),
    notes text,
    created_at timestamptz not null default now()
);

create index if not exists teacher_calendar_events_date_time_idx
    on public.teacher_calendar_events (event_date, start_time);
create index if not exists tarde_deportiva_matches_date_time_idx
    on public.tarde_deportiva_matches (match_date, start_time);

revoke all on table public.balones from public, anon;
revoke all on table public.teacher_calendar_events from public, anon;
revoke all on table public.tarde_deportiva_matches from public, anon;
grant usage on schema public to authenticated;
grant select, insert, update, delete on table public.balones to authenticated;
grant select, insert, delete on table public.teacher_calendar_events to authenticated;
grant select, insert, update, delete on table public.tarde_deportiva_matches to authenticated;

do $$
declare
    politica record;
begin
    for politica in
        select policyname, tablename
        from pg_policies
        where schemaname = 'public'
          and tablename in (
              'balones',
              'teacher_calendar_events',
              'tarde_deportiva_matches'
          )
    loop
        execute format(
            'drop policy %I on public.%I',
            politica.policyname,
            politica.tablename
        );
    end loop;
end
$$;

create policy "Authenticated users can manage balones"
    on public.balones for all to authenticated
    using (true)
    with check (true);

create policy "Authenticated users can view teacher calendar"
    on public.teacher_calendar_events for select to authenticated
    using (true);

create policy "Users can create their own teacher calendar events"
    on public.teacher_calendar_events for insert to authenticated
    with check (owner_id = (select auth.uid()));

create policy "Users can cancel their own teacher calendar events"
    on public.teacher_calendar_events for delete to authenticated
    using (owner_id = (select auth.uid()));

create policy "Authenticated users can manage Tarde Deportiva matches"
    on public.tarde_deportiva_matches for all to authenticated
    using (true)
    with check (true);

commit;
