package br.gov.sc.papanduva.sicpva;

import org.springframework.stereotype.Service;
import java.io.FileOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.awt.Color;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPCell;

@Service
public class GeradorPdfService {

    private static final String GEMINI_API_KEY = System.getenv("GEMINI_API_KEY");
    private static final String GOOGLE_SHEETS_URL = System.getenv("PLANILHA_URL");

    private String formatarTextoInteligente(String texto) {
        if (texto == null || texto.trim().isEmpty()) return "";
        texto = texto.toLowerCase();
        texto = texto.replaceAll("ihpone|iphone|iphnoe|ipohne|aifone|iphond|igphone|ifone", "iPhone");
        texto = texto.replaceAll("samsumg|sansung|samsung", "Samsung");
        texto = texto.replaceAll("motorola", "Motorola");
        texto = texto.replaceAll("hp\\b", "HP");
        texto = texto.replaceAll("dell", "Dell");
        texto = texto.replaceAll("lg\\b", "LG");
        texto = texto.replaceAll("gb\\b", "GB");
        texto = texto.replaceAll("tb\\b", "TB");
                     
        String[] palavras = texto.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : palavras) {
            if (p.length() > 0) {
                if (p.matches("de|da|do|das|dos|e|em|com|para|por|sem|sob") && sb.length() > 0) { 
                    sb.append(p).append(" "); 
                } 
                else if (p.equals("iPhone") || p.equals("HP") || p.equals("LG") || p.equals("GB") || p.equals("TB")) { 
                    sb.append(p).append(" "); 
                }
                else { 
                    sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(" "); 
                }
            }
        }
        return sb.toString().trim();
    }

    static class ItemSaneado {
        int numero, quantidade; String unidade, descricao;
        double valorUnit, menorValor, valorTotal, mediaSaneada;
        boolean criterioMenorPreco;
        java.util.List<CotacaoSaneada> cotacoes = new ArrayList<>();

        public void calcular() {
            if (!cotacoes.isEmpty()) {
                this.menorValor = Double.MAX_VALUE;
                java.util.List<Double> valores = new ArrayList<>();
                for (CotacaoSaneada c : cotacoes) { if(c.valor < this.menorValor) this.menorValor = c.valor; valores.add(c.valor); }
                Collections.sort(valores);
                double mediana = valores.size() % 2 == 0 ? (valores.get(valores.size()/2 - 1) + valores.get(valores.size()/2)) / 2.0 : valores.get(valores.size()/2);

                double somaSaneada = 0; int contValidas = 0;
                for (CotacaoSaneada c : cotacoes) {
                    if (cotacoes.size() >= 3) { 
                        if (c.valor > mediana * 1.5) {
                            c.discrepante = true;
                            c.situacao = "Desclassificada (Sobrepreço)";
                        } else if (c.valor < mediana * 0.7) {
                            c.discrepante = true;
                            c.situacao = "Desclassificada (Inexequível)";
                        } else {
                            c.discrepante = false;
                            c.situacao = "Válida";
                            somaSaneada += c.valor; contValidas++;
                        }
                    } 
                    else { 
                        c.discrepante = false; 
                        c.situacao = "Válida";
                        somaSaneada += c.valor; contValidas++; 
                    }
                }
                this.mediaSaneada = (contValidas > 0) ? (somaSaneada / contValidas) : this.menorValor;
                this.valorUnit = this.criterioMenorPreco ? this.menorValor : this.mediaSaneada;
            } else {
                this.mediaSaneada = this.valorUnit; this.menorValor = this.valorUnit;
            }
            this.valorTotal = this.quantidade * this.valorUnit;
        }
        public double getEconomia() { return (this.mediaSaneada - this.menorValor) * this.quantidade; }
    }

    static class CotacaoSaneada {
        String fornecedor, cnpj, marca, situacao; double valor; boolean discrepante = false;
        public CotacaoSaneada(String f, String c, String m, double v) { this.fornecedor = f; this.cnpj = c; this.marca = m; this.valor = v; }
    }

    private static Font getFonte(boolean negrito, float tamanho) throws Exception {
        BaseFont bf = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
        return new Font(bf, tamanho, negrito ? Font.BOLD : Font.NORMAL, Color.BLACK);
    }

    static class RodapeEvento extends PdfPageEventHelper {
        private String chave, tipoDoc;
        public RodapeEvento(String c, String t) { this.chave = c; this.tipoDoc = t; }
        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            try {
                Phrase p = new Phrase("Autenticidade " + tipoDoc + " Eletrônico - Chave: " + chave, getFonte(false, 8));
                ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_CENTER, p, (document.right() - document.left()) / 2 + document.leftMargin(), 20, 0);
            } catch (Exception e) {}
        }
    }

    public void processarEGerarTudo(DadosLicitacaoDTO dados, String hash) throws Exception {
        dados.setor = formatarTextoInteligente(dados.setor);
        dados.nomeResp = formatarTextoInteligente(dados.nomeResp);
        dados.cargoSec = formatarTextoInteligente(dados.cargoSec);
        dados.objeto = formatarTextoInteligente(dados.objeto);
        dados.localEntrega = formatarTextoInteligente(dados.localEntrega);

        boolean isReg = "REGULARIZACAO".equals(dados.natureza);
        boolean isCincatarina = dados.consorcioCincatarina != null && dados.consorcioCincatarina;
        
        String localEntrega = isReg ? "Já executado (Regularização)" : (dados.localEntrega != null ? dados.localEntrega : "Prefeitura Municipal");
        String prioridadeFinal = isReg ? "ALTA (Indenização)" : dados.prioridade;
        String termino = isReg ? "IMEDIATA" : (dados.dataTermino != null && !dados.dataTermino.isEmpty() ? dados.dataTermino + " Meses" : "IMEDIATO");

        java.util.List<String> extractions = new ArrayList<>();
        if (dados.itens != null) {
            for (DadosLicitacaoDTO.ItemDTO itemDTO : dados.itens) {
                extractions.add(itemDTO.descricao != null ? itemDTO.descricao : "");
                if (itemDTO.cotacoes != null) {
                    for (DadosLicitacaoDTO.CotacaoDTO cDTO : itemDTO.cotacoes) {
                        extractions.add(cDTO.fornecedor != null ? cDTO.fornecedor : "");
                        extractions.add(cDTO.marca != null ? cDTO.marca : "");
                    }
                }
            }
        }
        String rawItems = String.join("|@|", extractions);

        String amparoTexto = "";
        if (dados.amparoLegalEtp != null) {
            switch(dados.amparoLegalEtp) {
                case "A": amparoTexto = "a) Dispensa Padrão por Valor (Art. 75, I (obras) ou II (serviços e compras) da Lei 14.133/21)"; break;
                case "B": amparoTexto = "b) Compra Imediata de Baixo Valor (Art. 187)"; break;
                case "C": amparoTexto = "c) Inexigibilidade (Fornecedor Único - Art. 74 da Lei 14.133/21)"; break;
                case "D": amparoTexto = "d) Bens e Serviços Comuns"; break;
                default: amparoTexto = "Amparo não especificado.";
            }
        }

        String[] revisao = chamarIA(dados.setor, dados.objeto, dados.problema, dados.beneficio, isReg, rawItems, amparoTexto);
        
        if (revisao.length > 5 && revisao[5].trim().startsWith("ERRO")) {
            throw new Exception(revisao[5].trim().replace("ERRO: ", ""));
        }

        String setorFinal = revisao[0]; 
        String objetoFinal = revisao[1]; 
        String justificativaFinal = revisao[2];
        boolean precisaTI = revisao.length > 3 && revisao[3].trim().toUpperCase().contains("SIM");
        String itensCorrigidos = revisao.length > 4 ? revisao[4] : rawItems;

        String[] extractionsCorrected = itensCorrigidos.split("\\s*\\|@\\|\\s*");
        boolean canUseAIFix = (extractionsCorrected.length == extractions.size() && extractions.size() > 0);

        java.util.List<ItemSaneado> listaMestre = new ArrayList<>();
        double valorTotalGeral = 0.0; int contador = 1;
        StringBuilder sbQtd = new StringBuilder();
        int extractIdx = 0;

        if (dados.itens != null) {
            for (DadosLicitacaoDTO.ItemDTO itemDTO : dados.itens) {
                ItemSaneado it = new ItemSaneado();
                it.numero = contador++; 
                
                String descRaw = canUseAIFix ? extractionsCorrected[extractIdx++] : itemDTO.descricao;
                it.descricao = canUseAIFix ? descRaw : formatarTextoInteligente(descRaw); 
                
                it.quantidade = itemDTO.quantidade != null ? itemDTO.quantidade : 1;
                it.unidade = itemDTO.unidade != null ? itemDTO.unidade : "UND"; 
                it.criterioMenorPreco = itemDTO.criterioMenorPreco != null ? itemDTO.criterioMenorPreco : false;
                
                if (itemDTO.cotacoes != null) {
                    for (DadosLicitacaoDTO.CotacaoDTO cDTO : itemDTO.cotacoes) { 
                        String fornRaw = canUseAIFix ? extractionsCorrected[extractIdx++] : cDTO.fornecedor;
                        String marcaRaw = canUseAIFix ? extractionsCorrected[extractIdx++] : cDTO.marca;
                        
                        it.cotacoes.add(new CotacaoSaneada(
                            canUseAIFix ? fornRaw : formatarTextoInteligente(fornRaw),
                            cDTO.cnpj != null && !cDTO.cnpj.isEmpty() ? cDTO.cnpj : "Não informado",
                            canUseAIFix ? marcaRaw : formatarTextoInteligente(marcaRaw), 
                            cDTO.valor != null ? cDTO.valor : 0.0
                        )); 
                    }
                }
                it.calcular(); listaMestre.add(it); valorTotalGeral += it.valorTotal;
                
                sbQtd.append("Item ").append(it.numero).append(" - ").append(String.format("%02d", it.quantidade)).append(" (").append(it.unidade).append(") de ").append(it.descricao).append(";\n");
            }
        }

        if (dados.amparoLegalEtp != null && (dados.amparoLegalEtp.equals("A") || dados.amparoLegalEtp.equals("B"))) {
            if (valorTotalGeral > 59909.45) {
                throw new Exception("O valor total da compra (R$ " + String.format(new Locale("pt", "BR"), "%,.2f", valorTotalGeral) + ") ultrapassa o limite permitido para Dispensa. Revise os valores ou altere o Amparo Legal!");
            }
        }

        if ("B".equals(dados.amparoLegalEtp)) {
            if (valorTotalGeral > 14977.36) { 
                throw new Exception("Checklist Art. 187 Negado: O valor total (R$ " + String.format(new Locale("pt", "BR"), "%,.2f", valorTotalGeral) + ") ultrapassa 1/4 do limite da dispensa. Por favor, escolha a Dispensa Padrão (Opção A).");
            }
            if (dados.prazoEntregaDias != null && dados.prazoEntregaDias > 30) {
                throw new Exception("Checklist Art. 187 Negado: A entrega deve ocorrer em até 30 dias (" + dados.prazoEntregaDias + " dias informados). Altere o prazo ou a modalidade.");
            }
            if (isCincatarina) {
                throw new Exception("Checklist Art. 187 Negado: Você selecionou um Consórcio. O rito simplificado do Art. 187 só pode ser usado se o item NÃO estiver disponível em Atas ou Consórcios vigentes.");
            }
        }

        String textoLegalCompleto = "";
        if (dados.amparoLegalEtp != null) {
            switch(dados.amparoLegalEtp) {
                case "A": textoLegalCompleto = "a) Dispensa Padrão por Valor (Art. 75, I (obras) ou II (serviços e compras) da Lei 14.133/21): A elaboração do Estudo Técnico Preliminar (ETP) foi dispensada com base no Art. 47, inciso I, do Decreto Municipal nº 3.401/2024, visto que a contratação se enquadra nos limites de valor previstos no Art. 75, inciso I ou II da Lei Federal nº 14.133/2021, tratando-se de objeto de baixa complexidade e pronta entrega."; break;
                case "B": textoLegalCompleto = "b) Compra Imediata de Baixo Valor (Art. 187): A elaboração do Estudo Técnico Preliminar (ETP) foi dispensada com base no Art. 47, inciso I, do Decreto Municipal nº 3.401/2024. Ademais, o processo segue o rito simplificado do Art. 187 do mesmo Decreto, por se tratar de entrega imediata (até 30 dias) com valor inferior a 1/4 do limite de dispensa do Art. 75, inciso II, da Lei Federal nº 14.133/2021."; break;
                case "C": textoLegalCompleto = "c) Inexigibilidade (Fornecedor Único - Art. 74 da Lei 14.133/21): A elaboração do Estudo Técnico Preliminar (ETP) foi dispensada com base no Art. 47, inciso II, do Decreto Municipal nº 3.401/2024, por se tratar de contratação por inexigibilidade de licitação (Art. 74 da Lei Federal nº 14.133/2021), dado que a inviabilidade de competição torna desnecessária a análise comparativa de soluções."; break;
                case "D": textoLegalCompleto = "d) Bens e Serviços Comuns: A elaboração do Estudo Técnico Preliminar (ETP) foi dispensada com base no Art. 47, inciso IV, do Decreto Municipal nº 3.401/2024, uma vez que o objeto constitui bem/serviço comum, de natureza padronizada e baixa complexidade técnica, cujas especificações contidas neste Termo de Referência são suficientes para a perfeita caracterização do objeto e da estratégia de fornecimento."; break;
            }
        }

        if (isCincatarina) {
            justificativaFinal += " A presente aquisição/contratação será realizada via Consórcio/Ata Vigente, visando a economicidade e celeridade do processo consorciado. Consequentemente, ficam dispensados a elaboração do Termo de Referência e do Mapa de Cotação de Preços, valendo-se das especificações e valores já registrados no respectivo Consórcio.";
        }
        
        if (precisaTI) {
            justificativaFinal += " NOTA TÉCNICA: Por se tratar de equipamento ou serviço de informática, o presente processo fica condicionado à emissão de Parecer Técnico favorável pelo Departamento de Tecnologia da Informação (T.I) do Município.";
        }

        gerarPDF_DFD(setorFinal, dados.nomeResp, dados.matResp, dados.emailResp, dados.nomeSec, dados.cargoSec, objetoFinal, sbQtd.toString(), valorTotalGeral, termino, prioridadeFinal, textoLegalCompleto, justificativaFinal, hash);
        
        String assSolicitante = dados.nomeResp + "\nSERVIDOR REQUISITANTE - MAT: " + dados.matResp;

        if (!isCincatarina) {
            gerarPDF_TR(dados, setorFinal, assSolicitante, objetoFinal, listaMestre, valorTotalGeral, dados.prazoEntregaDias != null ? dados.prazoEntregaDias : 0, localEntrega, textoLegalCompleto, justificativaFinal, hash, isReg);
            gerarPDF_MapaCotacao(setorFinal, assSolicitante, objetoFinal, listaMestre, valorTotalGeral, hash);

            if (dados.gerarCertidao != null && dados.gerarCertidao) {
                String certNome = (dados.certidaoAssinatura != null && !dados.certidaoAssinatura.isEmpty()) ? dados.certidaoAssinatura : dados.nomeResp;
                String certCargo = (dados.certidaoCargo != null && !dados.certidaoCargo.isEmpty()) ? dados.certidaoCargo : "Servidor Requisitante / Mat: " + dados.matResp;
                String certData = (dados.certidaoData != null && !dados.certidaoData.isEmpty()) ? dados.certidaoData : LocalDate.now().format(DateTimeFormatter.ofPattern("dd 'de' MMMM 'de' yyyy", new Locale("pt", "BR")));
                gerarPDF_Certidao(certNome, certCargo, certData, hash);
            }
        }

        registarLogNaCloud(LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), setorFinal, objetoFinal, valorTotalGeral, amparoTexto, hash, isCincatarina ? "APENAS DFD (CONSÓRCIO)" : "DFD+TR+MAPA");
    }

    private void adicionarCabecalho(Document doc) throws Exception {
        try { 
            Image cabecalho = Image.getInstance("cabecalho.png"); 
            cabecalho.setAlignment(Element.ALIGN_CENTER); cabecalho.scaleToFit(350, 100); 
            doc.add(cabecalho); doc.add(new Paragraph("\n")); 
        } catch (Exception e) { 
            Paragraph h = new Paragraph("PREFEITURA MUNICIPAL DE PAPANDUVA\n\n", getFonte(true, 12)); 
            h.setAlignment(Element.ALIGN_CENTER); doc.add(h); 
        }
    }

    private void gerarPDF_DFD(String setor, String nome, String mat, String email, String sec, String cargo, String obj, String qtd, double valor, String term, String prio, String amparo, String just, String hash) throws Exception {
        Document doc = new Document(PageSize.A4); doc.setMargins(40, 40, 40, 40); 
        PdfWriter.getInstance(doc, new FileOutputStream("1_DFD_" + hash + ".pdf")).setPageEvent(new RodapeEvento(hash, "DFD")); doc.open();
        adicionarCabecalho(doc); Font fB = getFonte(true, 11); Font fN = getFonte(false, 11);
        Paragraph hd = new Paragraph("DOCUMENTO DE FORMALIZAÇÃO DE DEMANDA\n\n", getFonte(true, 12)); hd.setAlignment(Element.ALIGN_CENTER); doc.add(hd);
        
        doc.add(new Paragraph("1 - SECRETARIA REQUISITANTE", fB)); 
        Paragraph p1 = new Paragraph("Secretaria de " + setor, fN); p1.setSpacingAfter(12f); doc.add(p1);
        
        doc.add(new Paragraph("2 - RESPONSÁVEL", fB)); 
        Paragraph p2 = new Paragraph(nome + ", matrícula " + mat + ", e-mail " + email, fN); p2.setSpacingAfter(12f); doc.add(p2);
        
        doc.add(new Paragraph("3 - OBJETO E JUSTIFICATIVA", fB)); 
        Paragraph p3 = new Paragraph("Objeto: " + obj.toUpperCase() + ".\n" + just, fN); p3.setAlignment(Element.ALIGN_JUSTIFIED); p3.setSpacingAfter(12f); doc.add(p3);
        
        doc.add(new Paragraph("4 - QUANTIDADE E VALOR ESTIMADO", fB)); 
        Paragraph p4 = new Paragraph(qtd + "\nValor Global: R$ " + String.format(new Locale("pt", "BR"), "%,.2f", valor), fN); p4.setSpacingAfter(12f); doc.add(p4);
        
        doc.add(new Paragraph("5 - GRAU DE PRIORIDADE E TÉRMINO", fB)); 
        Paragraph p5 = new Paragraph("Prioridade: " + prio + ". Data Estimada: " + term + ".", fN); p5.setSpacingAfter(12f); doc.add(p5);
        
        doc.add(new Paragraph("6 - AMPARO LEGAL E DISPENSA DO ETP", fB));
        Paragraph pAmp = new Paragraph(amparo, fN); pAmp.setAlignment(Element.ALIGN_JUSTIFIED); pAmp.setSpacingAfter(12f); doc.add(pAmp);
        
        Paragraph ass = new Paragraph("\n\n\n\n\n__________________________________________\n" + sec.toUpperCase() + "\n" + cargo.toUpperCase(), fN); ass.setAlignment(Element.ALIGN_CENTER); doc.add(ass);
        doc.close();
    }

    private void gerarPDF_TR(DadosLicitacaoDTO dados, String setor, String assRequisitante, String obj, java.util.List<ItemSaneado> itens, double valorTotal, int prazo, String local, String amparo, String just, String hash, boolean isReg) throws Exception {
        Document doc = new Document(PageSize.A4); doc.setMargins(40, 40, 40, 40); 
        PdfWriter.getInstance(doc, new FileOutputStream("2_TR_" + hash + ".pdf")).setPageEvent(new RodapeEvento(hash, "TR")); doc.open();
        adicionarCabecalho(doc); Font fB = getFonte(true, 11); Font fN = getFonte(false, 11); Font fTH = getFonte(true, 9); Font fTC = getFonte(false, 9);
        Paragraph hd = new Paragraph("TERMO DE REFERÊNCIA (TR)\n\n", getFonte(true, 12)); hd.setAlignment(Element.ALIGN_CENTER); doc.add(hd);
        doc.add(new Paragraph("1 - OBJETO E ESPECIFICAÇÕES", fB)); Paragraph p1 = new Paragraph("Objeto: " + obj.toUpperCase() + "\n", fN); p1.setSpacingAfter(10f); doc.add(p1);
        PdfPTable table = new PdfPTable(6); table.setWidthPercentage(100); table.setWidths(new float[]{1f, 1.2f, 1.2f, 4f, 2f, 2f});
        String[] headers = {"Item", "Quant.", "Unid.", "Especificação", "Valor Unit.", "Valor Total"};
        for (String h : headers) { PdfPCell cell = new PdfPCell(new Phrase(h, fTH)); cell.setBackgroundColor(new Color(230, 230, 230)); table.addCell(cell); }
        for (ItemSaneado it : itens) { 
            table.addCell(new Phrase(String.valueOf(it.numero), fTC)); table.addCell(new Phrase(String.valueOf(it.quantidade), fTC)); table.addCell(new Phrase(it.unidade, fTC)); table.addCell(new Phrase(it.descricao, fTC)); 
            PdfPCell cU = new PdfPCell(new Phrase(String.format("R$ %,.2f", it.valorUnit), fTC)); cU.setHorizontalAlignment(Element.ALIGN_RIGHT); table.addCell(cU);
            PdfPCell cT = new PdfPCell(new Phrase(String.format("R$ %,.2f", it.valorTotal), fTC)); cT.setHorizontalAlignment(Element.ALIGN_RIGHT); table.addCell(cT);
        }
        PdfPCell cTotStr = new PdfPCell(new Phrase("TOTAL GERAL", fTH)); cTotStr.setColspan(5); cTotStr.setHorizontalAlignment(Element.ALIGN_RIGHT); table.addCell(cTotStr);
        PdfPCell cTotV = new PdfPCell(new Phrase(String.format("R$ %,.2f", valorTotal), fTH)); cTotV.setHorizontalAlignment(Element.ALIGN_RIGHT); table.addCell(cTotV); doc.add(table);
        
        doc.add(new Paragraph("\n2 - JUSTIFICATIVA E AMPARO LEGAL", fB)); 
        Paragraph p2 = new Paragraph(just + "\n\nAMPARO LEGAL E DISPENSA DE ETP:\n" + amparo, fN); 
        p2.setAlignment(Element.ALIGN_JUSTIFIED); p2.setSpacingAfter(12f); doc.add(p2);
        
        doc.add(new Paragraph("3 - EXECUÇÃO E ENTREGA", fB)); Paragraph p3 = new Paragraph(isReg ? "Objeto já executado/entregue (Regularização)." : "Prazo: " + prazo + " dias úteis. Local: " + local, fN); p3.setSpacingAfter(12f); doc.add(p3);
        
        if ((dados.reqPadrao != null && dados.reqPadrao) || (dados.reqSustentabilidade != null && dados.reqSustentabilidade) || (dados.reqCertificacao != null && dados.reqCertificacao) || (dados.reqGarantiaMeses != null && dados.reqGarantiaMeses > 0)) {
            doc.add(new Paragraph("4 - REQUISITOS DA CONTRATAÇÃO", fB));
            StringBuilder reqs = new StringBuilder();
            if (dados.reqPadrao != null && dados.reqPadrao) reqs.append("- PADRÃO: O produto deve ser novo, de primeiro uso, sem defeitos e em embalagem original.\n");
            if (dados.reqSustentabilidade != null && dados.reqSustentabilidade) reqs.append("- SUSTENTABILIDADE: Preferencialmente itens com menor impacto ambiental e embalagens recicláveis.\n");
            if (dados.reqCertificacao != null && dados.reqCertificacao) reqs.append("- CERTIFICAÇÃO: Deve possuir selo do INMETRO ou certificado da ANVISA (se aplicável).\n");
            if (dados.reqGarantiaMeses != null && dados.reqGarantiaMeses > 0) reqs.append("- GARANTIA: Mínima de ").append(dados.reqGarantiaMeses).append(" meses contra defeitos de fabricação.\n");

            Paragraph pReqs = new Paragraph(reqs.toString(), fN);
            pReqs.setSpacingAfter(12f);
            doc.add(pReqs);
        }

        // TÓPICO 5: CLASSIFICAÇÃO DO BEM
        if (dados.categoriaItem != null) {
            doc.add(new Paragraph("5 - CLASSIFICAÇÃO DO ITEM (Art. 47, § 1º)", fB));
            String catTexto = "";
            switch(dados.categoriaItem) {
                case "CONSUMO": catTexto = "CONSUMO/MANUTENÇÃO (Ex: Café, Água, Açúcar, Gêneros Alimentícios, Material de Expediente/Escolar, Limpeza/Saneantes, Papel Higiênico/Toalha, Pneus, Tubos/Conexões)."; break;
                case "SAUDE": catTexto = "SAÚDE/JUDICIAL (Ex: Medicamentos, Suplementos, Fórmulas, Materiais Odontológicos/Ambulatoriais/Hospitalares, Tiras de Glicose, Curativos, Soro, Testes, Fraldas)."; break;
                case "TECNOLOGIA": catTexto = "TECNOLOGIA E TI (Ex: Computadores, Notebooks, Impressoras, Monitores, Tablets, Projetores, Telas Interativas, Câmeras, Servidores, Rede, Nobreaks, Acessórios)."; break;
                case "MOBILIARIO": catTexto = "MOBILIÁRIO E EQUIPAMENTOS (Ex: Móveis de escritório, Cadeiras, Longarinas, Televisores, Eletrodomésticos, Utensílios, Luminárias/LED)."; break;
                case "OUTROS": catTexto = "OUTROS (Conforme Justificativa anexa - Art. 47, III)."; break;
            }
            Paragraph pCat = new Paragraph(catTexto, fN); pCat.setSpacingAfter(12f); doc.add(pCat);
        }

        // TÓPICO 6: PREFERÊNCIA ME/EPP
        if (dados.preferenciaMeEpp != null) {
            doc.add(new Paragraph("6 - PREFERÊNCIA ME/EPP LOCAL (Art. 190 Decreto 3401/2024 c/c Art. 49 LC 123/2006)", fB));
            String meTexto = "";
            if ("SIM".equals(dados.preferenciaMeEpp)) {
                meTexto = "SIM — Preferência aplicada. O fornecedor selecionado é ME/EPP sediada em Papanduva/SC.";
            } else {
                meTexto = "NÃO — Justificativa para a não aplicação da preferência local:\n";
                if (dados.justificativaMeEpp != null) {
                    switch(dados.justificativaMeEpp) {
                        case "NAO_IDENTIFICADA": meTexto += "( X ) NÃO FORAM IDENTIFICADAS ME/EPP LOCAIS capazes de atender o objeto (Art. 49, II, LC 123/2006)."; break;
                        case "EXCEDE_10": meTexto += "( X ) ME/EPP LOCAL IDENTIFICADA, porém preço superior ao menor preço obtido e a diferença excede 10%, não sendo vantajosa a contratação local (Art. 49, III, LC 123/2006)."; break;
                        case "CONSORCIO": meTexto += "( X ) CONTRATAÇÃO VIA CONSÓRCIO/ATA VIGENTE."; break;
                        case "DENTRO_10": meTexto += "( X ) ME/EPP LOCAL COM PREÇO SUPERIOR, mas dentro do limite de 10% sobre o menor preço obtido."; break;
                    }
                }
            }
            Paragraph pMe = new Paragraph(meTexto, fN); pMe.setSpacingAfter(12f); doc.add(pMe);
        }

        Paragraph ass = new Paragraph("\n\n\n\n\n__________________________________________\n" + assRequisitante.toUpperCase(), fN); ass.setAlignment(Element.ALIGN_CENTER); doc.add(ass);
        doc.close();
    }

    private void gerarPDF_MapaCotacao(String setor, String assRequisitante, String obj, java.util.List<ItemSaneado> itens, double valorTotal, String hash) throws Exception {
        Document doc = new Document(PageSize.A4); doc.setMargins(40, 40, 40, 40); 
        PdfWriter.getInstance(doc, new FileOutputStream("3_MAPA_" + hash + ".pdf")).setPageEvent(new RodapeEvento(hash, "MAPA")); doc.open();
        adicionarCabecalho(doc); Font fB = getFonte(true, 11); Font fN = getFonte(false, 11); Font fTH = getFonte(true, 9); Font fTC = getFonte(false, 9);
        Paragraph hd = new Paragraph("MAPA COMPROBATÓRIO DE PESQUISA DE PREÇOS\n", getFonte(true, 12)); hd.setAlignment(Element.ALIGN_CENTER); doc.add(hd);
        Paragraph pO = new Paragraph("Objeto: " + obj.toUpperCase() + "\n\n", fN); pO.setAlignment(Element.ALIGN_CENTER); doc.add(pO);
        double ecoGlobal = 0.0;
        for (ItemSaneado it : itens) {
            ecoGlobal += it.getEconomia();
            
            Paragraph pItem = new Paragraph("Item " + it.numero + " - " + it.descricao + " (" + it.quantidade + " " + it.unidade + ")", fB);
            pItem.setSpacingBefore(10f);
            pItem.setSpacingAfter(15f);
            doc.add(pItem);
            
            if(it.cotacoes.isEmpty()) { doc.add(new Paragraph("Valor Fixo Estimado: R$ " + String.format("%,.2f", it.valorUnit) + "\n\n", fN)); continue; }
            
            PdfPTable table = new PdfPTable(4); 
            table.setWidthPercentage(100); 
            table.setWidths(new float[]{3.5f, 2f, 2f, 2.5f});
            table.addCell(new PdfPCell(new Phrase("Fornecedor", fTH))); 
            table.addCell(new PdfPCell(new Phrase("Marca", fTH))); 
            table.addCell(new PdfPCell(new Phrase("Valor Ofertado", fTH)));
            table.addCell(new PdfPCell(new Phrase("Situação", fTH)));

            for (CotacaoSaneada c : it.cotacoes) {
                PdfPCell cForn = new PdfPCell(new Phrase(c.fornecedor + "\nCNPJ: " + c.cnpj, fTC));
                table.addCell(cForn); 
                table.addCell(new Phrase(c.marca, fTC));
                
                PdfPCell cV = new PdfPCell(new Phrase(String.format("R$ %,.2f", c.valor), fTC)); 
                cV.setHorizontalAlignment(Element.ALIGN_RIGHT); 
                table.addCell(cV);
                
                Font fStatus = c.discrepante ? getFonte(true, 8) : fTC;
                PdfPCell cS = new PdfPCell(new Phrase(c.situacao, fStatus));
                cS.setHorizontalAlignment(Element.ALIGN_CENTER);
                table.addCell(cS);
            }
            doc.add(table);
            Paragraph pRes = new Paragraph("Critério: " + (it.criterioMenorPreco ? "Menor Preço" : "Média Saneada") + " | Ref: R$ " + String.format("%,.2f", it.valorUnit) + " | Total do Item: R$ " + String.format("%,.2f", it.valorTotal) + "\n\n", fN); pRes.setAlignment(Element.ALIGN_RIGHT); doc.add(pRes);
        }
        Paragraph pT = new Paragraph("VALOR GLOBAL: R$ " + String.format("%,.2f", valorTotal) + "\nECONOMIA GERADA: R$ " + String.format("%,.2f", ecoGlobal), getFonte(true, 12)); pT.setAlignment(Element.ALIGN_RIGHT); doc.add(pT);
        Paragraph ass = new Paragraph("\n\n\n\n\n__________________________________________\n" + assRequisitante.toUpperCase(), fN); ass.setAlignment(Element.ALIGN_CENTER); doc.add(ass);
        doc.close();
    }

    private void gerarPDF_Certidao(String nome, String cargo, String data, String hash) throws Exception {
        Document doc = new Document(PageSize.A4); doc.setMargins(60, 60, 60, 60); 
        PdfWriter.getInstance(doc, new FileOutputStream("4_CERTIDAO_" + hash + ".pdf")).setPageEvent(new RodapeEvento(hash, "CERTIDÃO"));
        doc.open();
        
        Font fB = getFonte(true, 12);
        Font fN = getFonte(false, 12);

        Paragraph hd = new Paragraph("CERTIDÃO DE DISPENSA (ETP E PARECER JURÍDICO)\n\n\n", fB);
        hd.setAlignment(Element.ALIGN_CENTER);
        doc.add(hd);

        String texto = "Certifico, para os devidos fins, que a presente contratação se enquadra nas hipóteses do Art. 187 do Decreto Municipal, por possuir valor inferior a 1/4 do limite de dispensa (Art. 75, II, Lei 14.133/21) e entrega imediata (até 30 dias). Assim, ficam DISPENSADOS:\n\n" +
                "1. O Estudo Técnico Preliminar (ETP), conforme Art. 50, do Decreto nº 3.401/2024.\n" +
                "2. A manifestação jurídica (Parecer da Procuradoria), conforme inciso II do Art. 187 do Decreto.\n" +
                "3. A minuta de contrato formal, sendo substituída pela Nota de Empenho, conforme Art. 95, I da Lei 14.133/21.\n\n";

        Paragraph pBody = new Paragraph(texto, fN);
        pBody.setAlignment(Element.ALIGN_JUSTIFIED);
        pBody.setLeading(1.5f * 12f);
        doc.add(pBody);

        Paragraph pData = new Paragraph("Papanduva/SC, " + data + ".\n\n\n\n\n\n", fN);
        pData.setAlignment(Element.ALIGN_RIGHT);
        doc.add(pData);

        Paragraph pAss = new Paragraph("__________________________________________\n" + nome.toUpperCase() + "\n" + cargo.toUpperCase(), fB);
        pAss.setAlignment(Element.ALIGN_CENTER);
        doc.add(pAss);

        doc.close();
    }

    private String[] chamarIA(String setor, String objeto, String problema, String beneficio, boolean isReg, String rawItems, String amparoTexto) {
        String baseJust = "A contratação justifica-se para sanar: " + problema + ". Benefício: " + beneficio;
        if(isReg) baseJust += ". A presente busca a regularização da despesa já executada.";
        String[] fallback = {setor, objeto, baseJust, "NAO", rawItems, "OK"};
        if (GEMINI_API_KEY == null || GEMINI_API_KEY.isEmpty()) return fallback;
        try {
            String prompt = "Revisor de licitações. 1: Corrija ortografia de: '" + setor + "'. 2: Corrija ortografia de: '" + objeto + "'. 3: Una este problema ('" + problema + "') e benefício ('" + beneficio + "') num parágrafo formal jurídico." + (isReg ? " Inclua que é regularização de despesa." : "") + " 4: Responda apenas SIM ou NAO: O objeto '" + objeto + "' envolve tecnologia, computadores, internet, software, impressoras ou celulares? 5: Atue como corretor ortográfico. Abaixo há uma lista de produtos, marcas e fornecedores separados pelo delimitador '|@|'. Corrija a ortografia e aplique Title Case (ex: 'samsumg' vira 'Samsung', 'iphnoe' ou 'igphone' vira 'iPhone'). NÃO altere a ordem, NÃO adicione itens e MANTENHA o delimitador '|@|' exato entre eles: [" + rawItems + "]. 6: Atue como auditor do Tribunal de Contas. Avalie se o amparo legal escolhido ('" + amparoTexto + "') faz sentido para o objeto ('" + objeto + "'). Atenção: Celulares, computadores, veículos e material de expediente SÃO BENS COMUNS e NÃO PODEM usar Inexigibilidade (Art. 74). Se o amparo for uma Inexigibilidade para um item comum ou absurdo, retorne EXATAMENTE começando com a palavra 'ERRO: ' seguido de uma explicação do porquê ser ilegal e sugerindo a Dispensa de Licitação. Se o amparo estiver coerente com o objeto, retorne APENAS a palavra 'OK'. RETORNE EXATAMENTE 6 TEXTOS SEPARADOS POR '###'.";
            
            String promptSeguro = prompt.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
            String json = "{\"contents\":[{\"parts\":[{\"text\":\"" + promptSeguro + "\"}]}]}";
            HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder().uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + GEMINI_API_KEY)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
            int idx = r.body().indexOf("\"text\": \"");
            if (idx != -1) {
                String proc = r.body().substring(idx + 9, r.body().indexOf("\"", idx + 9)).replace("\\n", "").replace("\\\"", "\"");
                String[] pts = proc.split("###");
                if (pts.length >= 6) { 
                    for(int i=0;i<6;i++) pts[i] = pts[i].trim().replaceFirst("^[1-6][\\.\\-\\\\) :]\\s*", ""); 
                    return pts; 
                }
            }
            return fallback;
        } catch (Exception e) { return fallback; }
    }

    private void registarLogNaCloud(String data, String setor, String objeto, double valor, String amparo, String chave, String docs) {
        if (GOOGLE_SHEETS_URL == null || GOOGLE_SHEETS_URL.isEmpty()) return;
        try {
            String jsonPayload = String.format(new Locale("pt", "BR"), "{\"dataEmissao\": \"%s\", \"setor\": \"%s\", \"objeto\": \"%s\", \"valor\": \"R$ %,.2f\", \"amparo\": \"%s\", \"chave\": \"%s\", \"documentos\": \"%s\"}", data, setor.replace("\"", "\\\""), objeto.replace("\"", "\\\""), valor, amparo.replace("\"", "\\\""), chave, docs);
            HttpClient.newHttpClient().send(HttpRequest.newBuilder().uri(URI.create(GOOGLE_SHEETS_URL)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {}
    }
}