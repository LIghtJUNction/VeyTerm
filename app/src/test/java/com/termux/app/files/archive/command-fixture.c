/* Synthetic PUBLIC CLI process for deterministic lifecycle/output-bound tests.
 * Not a decoder, never shipped. Actual decoder feature tests use official UnRAR.
 */
#include <stdio.h>
#include <string.h>
#include <unistd.h>
int main(int argc, char **argv) {
    const char *archive = argv[argc - 1];
    if (strstr(archive, "sleep")) { for (;;) pause(); }
    if (strstr(archive, "stderr")) { for (int i=0;i<70000;i++) fputc('x',stderr); return 0; }
    if (strcmp(argv[1], "lt") == 0) {
        printf("\nArchive: %s\nDetails: RAR 5\n\n", archive);
        printf("        Name: safe\n        Type: File\n        Size: 1\n Packed size: 1\n       Ratio: 100%%\n  Attributes: -rw-r--r--\n       CRC32: 8CDC1683\n     Host OS: Unix\n Compression: RAR 5.0(v50) -m0 -md=128k\n");
        if (strstr(archive, "duplicate")) printf("        Size: 1\n");
        printf("\n"); return 0;
    }
    if (strcmp(argv[1], "p") == 0) {
        fputc('x',stdout); fflush(stdout);
        if (strstr(archive, "overflow")) { fputc('y',stdout); return 0; }
        usleep(100000);
        return 3; /* A late checksum error after all declared bytes. */
    }
    return 3;
}
