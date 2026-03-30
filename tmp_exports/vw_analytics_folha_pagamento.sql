create or replace view public.vw_analytics_folha_pagamento as
with active_team as (
    select
        em.funcionario_id,
        em.equipe_id,
        em.papel,
        row_number() over (
            partition by em.funcionario_id
            order by coalesce(em.data_entrada, date '1900-01-01') desc, em.id desc
        ) as rn
    from public.equipe_membros em
    where em.data_saida is null
),
event_totals as (
    select
        ef.folha_pagamento_id,
        count(*) as qtd_eventos,
        count(*) filter (where ef.tipo = 'PROVENTO') as qtd_proventos,
        count(*) filter (where ef.tipo = 'DESCONTO') as qtd_descontos,
        count(*) filter (where ef.tipo = 'ADICIONAL') as qtd_adicionais,
        coalesce(sum(ef.valor) filter (where ef.tipo = 'PROVENTO'), 0)::numeric(18,2) as valor_proventos,
        coalesce(sum(ef.valor) filter (where ef.tipo = 'DESCONTO'), 0)::numeric(18,2) as valor_descontos_eventos,
        coalesce(sum(ef.valor) filter (where ef.tipo = 'ADICIONAL'), 0)::numeric(18,2) as valor_adicionais,
        count(distinct ef.descricao) as qtd_tipos_evento,
        string_agg(distinct ef.descricao, ', ' order by ef.descricao) as eventos_descricao
    from public.eventos_folha ef
    group by ef.folha_pagamento_id
),
base as (
    select
        fp.id as folha_pagamento_id,
        fp.funcionario_id,
        f.nome_completo,
        i.codinome,
        i.universo,
        i.exposicao_publica,
        c.id as cargo_id,
        c.nome as cargo,
        d.id as departamento_id,
        d.nome as departamento,
        at.equipe_id,
        e.nome as equipe,
        at.papel as papel_equipe,
        b.id as base_id,
        b.nome as base,
        b.tipo as tipo_base,
        b.sigilo as sigilo_base,
        fp.ano,
        fp.mes,
        make_date(fp.ano, fp.mes, 1) as competencia,
        fp.data_pagamento,
        fp.salario_bruto::numeric(18,2) as salario_bruto,
        fp.total_descontos::numeric(18,2) as total_descontos,
        fp.salario_liquido::numeric(18,2) as salario_liquido,
        coalesce(et.qtd_eventos, 0) as qtd_eventos,
        coalesce(et.qtd_proventos, 0) as qtd_proventos,
        coalesce(et.qtd_descontos, 0) as qtd_descontos,
        coalesce(et.qtd_adicionais, 0) as qtd_adicionais,
        coalesce(et.qtd_tipos_evento, 0) as qtd_tipos_evento,
        coalesce(et.valor_proventos, 0)::numeric(18,2) as valor_proventos,
        coalesce(et.valor_descontos_eventos, 0)::numeric(18,2) as valor_descontos_eventos,
        coalesce(et.valor_adicionais, 0)::numeric(18,2) as valor_adicionais,
        et.eventos_descricao
    from public.folhas_pagamento fp
    join public.funcionarios f on f.id = fp.funcionario_id
    left join public.identidades_secretas i on i.funcionario_id = f.id
    left join public.cargos c on c.id = f.cargo_id
    left join public.departamentos d on d.id = f.departamento_id
    left join active_team at on at.funcionario_id = f.id and at.rn = 1
    left join public.equipes e on e.id = at.equipe_id
    left join public.bases b on b.id = e.base_principal_id
    left join event_totals et on et.folha_pagamento_id = fp.id
)
select
    b.folha_pagamento_id,
    b.funcionario_id,
    b.nome_completo,
    b.codinome,
    b.universo,
    b.exposicao_publica,
    b.cargo_id,
    b.cargo,
    b.departamento_id,
    b.departamento,
    b.equipe_id,
    b.equipe,
    b.papel_equipe,
    b.base_id,
    b.base,
    b.tipo_base,
    b.sigilo_base,
    b.ano,
    b.mes,
    b.competencia,
    b.data_pagamento,
    b.salario_bruto,
    b.total_descontos,
    b.salario_liquido,
    b.qtd_eventos,
    b.qtd_proventos,
    b.qtd_descontos,
    b.qtd_adicionais,
    b.qtd_tipos_evento,
    b.valor_proventos,
    b.valor_descontos_eventos,
    b.valor_adicionais,
    (b.valor_adicionais - b.valor_descontos_eventos)::numeric(18,2) as saldo_eventos,
    (b.salario_liquido - b.salario_bruto)::numeric(18,2) as saldo_liquido_vs_bruto,
    round(b.total_descontos / nullif(b.salario_bruto, 0), 4) as pct_desconto,
    round(b.salario_liquido / nullif(b.salario_bruto, 0), 4) as pct_liquido,
    round(b.valor_adicionais / nullif(b.salario_bruto, 0), 4) as pct_adicionais_sobre_bruto,
    round(b.valor_descontos_eventos / nullif(b.salario_bruto, 0), 4) as pct_eventos_desconto_sobre_bruto,
    case
        when b.salario_bruto < 12000 then 'ATE_12K'
        when b.salario_bruto < 25000 then '12K_25K'
        when b.salario_bruto < 40000 then '25K_40K'
        when b.salario_bruto < 60000 then '40K_60K'
        else '60K_PLUS'
    end as faixa_salario_bruto,
    case
        when b.salario_liquido < 10000 then 'ATE_10K'
        when b.salario_liquido < 20000 then '10K_20K'
        when b.salario_liquido < 35000 then '20K_35K'
        when b.salario_liquido < 50000 then '35K_50K'
        else '50K_PLUS'
    end as faixa_salario_liquido,
    case
        when b.total_descontos / nullif(b.salario_bruto, 0) < 0.18 then 'ATE_18_PCT'
        when b.total_descontos / nullif(b.salario_bruto, 0) < 0.22 then '18_22_PCT'
        when b.total_descontos / nullif(b.salario_bruto, 0) < 0.26 then '22_26_PCT'
        else '26_PCT_PLUS'
    end as faixa_pct_desconto,
    case
        when b.valor_adicionais = 0 then 'SEM_ADICIONAL'
        when b.valor_adicionais < 1000 then 'ADICIONAL_BAIXO'
        when b.valor_adicionais < 5000 then 'ADICIONAL_MEDIO'
        else 'ADICIONAL_ALTO'
    end as faixa_valor_adicionais,
    case
        when b.cargo ilike '%agente%' or b.departamento ilike '%seguran%' then 'SECURITY'
        when b.cargo ilike '%piloto%' or b.cargo ilike '%comandante%' or b.departamento ilike '%explora%' then 'OPERATIONS'
        when b.cargo ilike '%cientista%' or b.cargo ilike '%engenheiro%' or b.departamento ilike '%p&d%' or b.departamento ilike '%pesquisa%' then 'RND'
        when b.cargo ilike '%repórter%' or b.cargo ilike '%reporter%' or b.departamento ilike '%comunica%' or b.departamento ilike '%jornalis%' then 'MEDIA'
        when b.cargo ilike 'CEO%' or b.cargo ilike 'Diretor%' then 'EXEC'
        else 'GENERAL'
    end as payroll_profile,
    case
        when b.qtd_adicionais > 0 and b.qtd_descontos > 0 then 'MISTA'
        when b.qtd_adicionais > 0 then 'COM_ADICIONAL'
        when b.qtd_descontos > 0 then 'COM_DESCONTO'
        else 'BASICA'
    end as composicao_folha,
    b.eventos_descricao
from base b;
