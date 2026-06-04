package br.gov.sc.papanduva.sicpva;

import java.util.List;

public class DadosLicitacaoDTO {
    public String natureza, setor, nomeResp, matResp, emailResp, nomeSec, cargoSec;
    public String objeto, problema, beneficio, prioridade, dataTermino;
    public Integer prazoEntregaDias;
    
    public String localEntrega;
    public Boolean consorcioCincatarina;
    public String amparoLegalEtp;

    // Novas Variaveis da Atualizacao
    public String categoriaItem;
    public String preferenciaMeEpp;
    public String justificativaMeEpp;

    public Boolean reqPadrao, reqSustentabilidade, reqCertificacao;
    public Integer reqGarantiaMeses;

    public Boolean gerarCertidao;
    public String certidaoAssinatura, certidaoCargo, certidaoData;

    public List<ItemDTO> itens;

    public static class ItemDTO {
        public Integer quantidade;
        public String unidade, descricao;
        public Boolean criterioMenorPreco;
        public List<CotacaoDTO> cotacoes;
    }

    public static class CotacaoDTO {
        public String fornecedor, cnpj, marca;
        public Double valor;
    }
}