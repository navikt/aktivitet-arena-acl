alter table aktivitet
    add constraint oppfolgingsperioder_fk
        foreign key (oppfolgingsperiode_uuid)
            references oppfolgingsperioder(id);

alter table deltaker_aktivitet_mapping
    add constraint oppfolgingsperioder_fk
        foreign key (oppfolgingsperiode_id)
            references oppfolgingsperioder(id);

alter table deltaker_aktivitet_mapping drop column oppfolgingsperiode_slutttidspunkt;
alter table aktivitet drop column oppfolgingsperiode_slutt_tidspunkt;
