insert into oppfolgingsperioder(id, slutt)
select
    deltaker_aktivitet_mapping.oppfolgingsperiode_id,
    COALESCE(aktivitet.oppfolgingsperiode_slutt_tidspunkt , deltaker_aktivitet_mapping.oppfolgingsperiode_slutttidspunkt) as ny_slutt
from deltaker_aktivitet_mapping
    left join aktivitet on deltaker_aktivitet_mapping.oppfolgingsperiode_id = aktivitet.oppfolgingsperiode_uuid
        and aktivitet.id = deltaker_aktivitet_mapping.aktivitetskort_id
on conflict (id) do update
-- Bare oppdater slutt hvis innkommende rad har en sluttdato
set slutt = COALESCE(excluded.slutt , oppfolgingsperioder.slutt)
