package br.gov.sc.papanduva.sicpva;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api")
public class SICPVAController {

    @Autowired
    private GeradorPdfService geradorPdfService;

    @PostMapping(value = "/gerar", produces = "application/zip")
    public ResponseEntity<byte[]> gerarDocumentos(@RequestBody DadosLicitacaoDTO dados) {
        try {
            String hash = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            
            geradorPdfService.processarEGerarTudo(dados, hash);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ZipOutputStream zos = new ZipOutputStream(baos);
            
            // AGORA COM OS 4 ARQUIVOS
            String[] nomesArquivos = {"1_DFD_" + hash + ".pdf", "2_TR_" + hash + ".pdf", "3_MAPA_" + hash + ".pdf", "4_CERTIDAO_" + hash + ".pdf"};
            
            for (String nomeArq : nomesArquivos) {
                File pdfFile = new File(nomeArq);
                if (pdfFile.exists()) {
                    ZipEntry entry = new ZipEntry(nomeArq);
                    zos.putNextEntry(entry);
                    Files.copy(pdfFile.toPath(), zos);
                    zos.closeEntry();
                    pdfFile.delete();
                }
            }
            zos.close();

            File pastaChaves = new File("banco_de_chaves");
            if(!pastaChaves.exists()) pastaChaves.mkdirs();
            new File("banco_de_chaves/" + hash + ".key").createNewFile();

            String safeObjeto = dados.objeto.replaceAll("[^a-zA-Z0-9À-ÿ\\s]", "").trim();
            if (safeObjeto.length() > 30) safeObjeto = safeObjeto.substring(0, 30);
            String safeSetor = dados.setor.replaceAll("[^a-zA-Z0-9À-ÿ\\s]", "").trim();
            String zipName = "Documentos - " + safeObjeto + " - " + safeSetor + ".zip";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.valueOf("application/zip"));
            headers.setContentDispositionFormData("attachment", zipName);

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(baos.toByteArray());

        } catch (Throwable e) {
            e.printStackTrace();
            return ResponseEntity.status(500)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body((e.getMessage()).getBytes());
        }
    }

    @GetMapping("/verificar")
    public ResponseEntity<String> verificarChave(@RequestParam String chave) {
        File chaveNova = new File("banco_de_chaves/" + chave + ".key");
        File chaveAntiga = new File("1_DFD_" + chave + ".pdf");
        
        if (chaveNova.exists() || chaveAntiga.exists()) {
            return ResponseEntity.ok("✅ CHAVE AUTÊNTICA: O Processo " + chave + " foi gerado e homologado pelo sistema.");
        } else {
            return ResponseEntity.ok("❌ CHAVE INVÁLIDA: O código " + chave + " não corresponde a nenhum documento recente.");
        }
    }
}