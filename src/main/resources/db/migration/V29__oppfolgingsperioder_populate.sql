with oppfolgingsperioder_select as (
    select distinct on (deltaker_aktivitet_mapping.oppfolgingsperiode_id)
        deltaker_aktivitet_mapping.oppfolgingsperiode_id as id,
        COALESCE(aktivitet.oppfolgingsperiode_slutt_tidspunkt , deltaker_aktivitet_mapping.oppfolgingsperiode_slutttidspunkt) as slutt
    from deltaker_aktivitet_mapping
        left join aktivitet on deltaker_aktivitet_mapping.oppfolgingsperiode_id = aktivitet.oppfolgingsperiode_uuid
            and aktivitet.id = deltaker_aktivitet_mapping.aktivitetskort_id
    ORDER BY id, slutt desc
)
insert into oppfolgingsperioder(id, slutt)
select id, slutt from oppfolgingsperioder_select;
