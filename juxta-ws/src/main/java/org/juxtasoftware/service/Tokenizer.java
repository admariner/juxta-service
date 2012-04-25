package org.juxtasoftware.service;

import static eu.interedition.text.query.Criteria.and;
import static eu.interedition.text.query.Criteria.annotationName;
import static eu.interedition.text.query.Criteria.text;
import static org.juxtasoftware.Constants.TOKEN_NAME;

import java.io.IOException;
import java.io.Reader;
import java.util.List;
import java.util.Set;

import eu.interedition.text.TextConsumer;
import org.juxtasoftware.dao.ComparisonSetDao;
import org.juxtasoftware.model.CollatorConfig;
import org.juxtasoftware.model.ComparisonSet;
import org.juxtasoftware.model.Witness;
import org.juxtasoftware.util.BackgroundTaskSegment;
import org.juxtasoftware.util.BackgroundTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import eu.interedition.text.Annotation;
import eu.interedition.text.AnnotationRepository;
import eu.interedition.text.Range;
import eu.interedition.text.Text;
import eu.interedition.text.TextRepository;
import eu.interedition.text.mem.SimpleAnnotation;

@Service
@Transactional
public class Tokenizer {
    private static final int BATCH_SIZE = 15000;
    private static final Logger LOG = LoggerFactory.getLogger(Tokenizer.class);

    @Autowired private AnnotationRepository annotationRepository;
    @Autowired private TextRepository textRepository;
    @Autowired private ComparisonSetDao comparisonSetDao;
    private boolean filterPunctuation = true;
    private boolean filterWhitespace = true;

    /**
     * Break up the text of the all witnesses in the comparison set on whitespace boundaries. 
     * If the configuration specifies that punctuation also be ignored, 
     * tokenize on punctuation as well.
     * 
     * @param comparisonSet
     * @param config
     * @param taskStatus
     * 
     * @throws IOException
     */
    public void tokenize(ComparisonSet comparisonSet, CollatorConfig config, BackgroundTaskStatus taskStatus) throws IOException {
        final Set<Witness> witnesses = comparisonSetDao.getWitnesses(comparisonSet);
        final BackgroundTaskSegment ts = taskStatus.add(1, new BackgroundTaskSegment(witnesses.size()));
        
        // look into the cfg and see if we should be ignoring punctuaion. If so,
        // set the flag so we can tokenize on ws and punctuation. this keeps
        // results consistent with the desktop juxta.
        this.filterPunctuation = config.isFilterPunctuation(); 
        this.filterWhitespace = config.isFilterWhitespace();
        
        taskStatus.setNote("Tokenizing " + comparisonSet);
        
        for (Witness witness : witnesses) {
            taskStatus.setNote("Tokenizing '" + witness.getName() + "'");
            
            tokenize(witness);
            
            ts.incrementValue();            
        }
    }
    
    private void tokenize(final Witness witness) throws IOException {
        // purge any prior tokens for this witness
        final Text text = witness.getText();
        Preconditions.checkNotNull(text);
        this.annotationRepository.delete(and(text(text), annotationName(TOKEN_NAME)));

        final Range fragment = witness.getFragment();
        this.textRepository.read(text, new TextConsumer() {

            private List<Annotation> tokens = Lists.newArrayListWithExpectedSize(BATCH_SIZE);

            public void read(Reader tokenText, long contentLength) throws IOException {
                LOG.info("Tokenizing " + witness);

                int numTokens = 0;
                int offset = 0;
                int start = -1;
                boolean whitespaceRun = false;

                do {
                    final int read = tokenText.read();
                    if (read < 0) {
                        if (start != -1) {
                            createToken(text, start, offset);
                            numTokens++;
                        }
                        break;
                    }

                    // make sure we are in bounds for the requested text fragment
                    // -- or no fragment was specified at all
                    if ( fragment.equals(Range.NULL) || (offset >= fragment.getStart() && offset < fragment.getEnd()) ) {
                        
                        // track start of whitespace run if whitespace is not ignored
                        if (filterWhitespace == false && Character.isWhitespace(read) && start == -1) {
                            start = offset;
                            whitespaceRun = true;
                        } else if (isTokenChar(read)) {
                            // end whitespace runs on non whitespace chars. Create
                            // a token containing the spaces
                            if (whitespaceRun) {
                                createToken(text, start, offset);
                                numTokens++;
                                start = -1;
                                whitespaceRun = false;
                            }
                            
                            // start a new token
                            if (start == -1) {
                                start = offset;
                            }
                        } else {
                            // Either whitespace or punctuation found. Notmally, this would end a
                            // token. Behavior is different if whitespace is not being ignored!
                            // Simple case: punctuation - end and create a new token.
                            // Harder case: non-filtered whitespace - if we are in the midst of a 
                            // whitespace run DONT create a token, just keep accumulating the whitespace
                            if (start != -1 && (isPunctuation(read) || filterWhitespace == false && whitespaceRun == false )) {
                                createToken(text, start, offset);
                                start = -1;
                                
                                // if whitespace ended the token and is not being ignored, start
                                // a new token with the whitespace. This ensures that all whitespace
                                // is included in the collation
                                if (filterWhitespace == false && Character.isWhitespace(read) ) {
                                    start = offset;
                                    whitespaceRun = true;
                                }
                            }
                        }
                    }

                    offset++;
                } while (true);

                if (!tokens.isEmpty()) {
                    write();
                }
                LOG.trace(witness + " has " + numTokens + " token(s)");
            }

            private void createToken(Text text, int start, int end) {
                tokens.add(new SimpleAnnotation(text, TOKEN_NAME, new Range(start, end), null));
                if ((tokens.size() % BATCH_SIZE) == 0) {
                    write();
                }
            }

            private void write() {
                annotationRepository.create(tokens);
                tokens.clear();
            }
        });
    }
    
    private boolean isPunctuation( int c ) {
        return ( Character.isWhitespace(c) == false &&
                 Character.isLetter(c) == false &&
                 Character.isDigit(c) == false );
    }
    
    private boolean isTokenChar(int c) {
        if (Character.isWhitespace(c)) {
            return false;
        }
        
        if ( this.filterPunctuation ) {
            if (Character.isLetter(c) || Character.isDigit(c)) {
                return true;
            }
            return false;
        } 
        return true;
    }
}
