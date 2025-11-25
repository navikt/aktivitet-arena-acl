create table oppfolgingsperioder (
    id uuid primary key,
    -- Nye perioder har startdato men vi har ikke lagret den og den er derfor ikke med overalt
    start TIMESTAMP with time zone,
    slutt TIMESTAMP with time zone,
    created_at TIMESTAMP default now(),
    updated_at TIMESTAMP default now()
);
