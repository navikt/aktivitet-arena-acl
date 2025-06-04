ALTER TABLE deltaker_aktivitet_mapping RENAME TO deltaker_aktivitet_mapping_old;
/* Recreated because previous table had primary key on deltaker_id, aktivitet_kategori,
   oppfolgingsperiode_id AND aktivitet_id which allowed several aktivitet_ids on the same
   periode for the same deltaker_id and aktivitet_kategori.
*/
CREATE TABLE deltaker_aktivitet_mapping (
    oppfolgingsperiode_id uuid,
    aktivitetskort_id uuid,
    aktivitet_kategori varchar,
    deltaker_id numeric,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    /* Order of primary key is important */
    PRIMARY KEY (deltaker_id, aktivitet_kategori, oppfolgingsperiode_id),
    UNIQUE (aktivitetskort_id)
);
