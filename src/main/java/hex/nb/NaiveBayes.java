package hex.nb;

import hex.FrameTask.DataInfo;
import water.*;
import water.Job.ModelJob;
import water.api.DocGen;
import water.fvec.*;
import water.util.RString;
import water.util.Utils;


/**
 * Naive Bayes
 * This is an algorithm for computing the conditional a-posterior probabilities of a categorical
 * response from independent predictors using Bayes rule.
 * <a href = "http://en.wikipedia.org/wiki/Naive_Bayes_classifier">Naive Bayes on Wikipedia</a>
 * <a href = "http://cs229.stanford.edu/notes/cs229-notes2.pdf">Lecture Notes by Andrew Ng</a>
 * @author anqi_fu
 *
 */
public class NaiveBayes extends ModelJob {
  static final int API_WEAVER = 1;
  static public DocGen.FieldDoc[] DOC_FIELDS;
  static final String DOC_GET = "naive bayes";

  @API(help = "Laplace smoothing parameter", filter = Default.class, lmin = 0, lmax = 100000, json = true)
  public int laplace = 0;

  @API(help = "Drop columns with more than 20% missing values", filter = Default.class)
  public boolean drop_na_cols = true;

  @Override protected void execImpl() {
    Frame fr = DataInfo.prepareFrame(source, response, ignored_cols, false, false, drop_na_cols);
    DataInfo dinfo = new DataInfo(fr, 1, false, false, false);
    NBTask tsk = new NBTask(this, dinfo).doAll(dinfo._adaptedFrame);
    NBModel myModel = buildModel(dinfo, tsk, laplace);
    myModel.delete_and_lock(self());
    myModel.unlock(self());
  }

  @Override protected void init() {
    super.init();
    if(!response.isEnum())
      throw new IllegalArgumentException("Response must be a categorical column");
  }

  @Override protected Response redirect() {
    return NBProgressPage.redirect(this, self(), dest());
  }

  public static String link(Key src_key, String content) {
    RString rs = new RString("<a href='/2/NaiveBayes.query?%key_param=%$key'>%content</a>");
    rs.replace("key_param", "source");
    rs.replace("key", src_key.toString());
    rs.replace("content", content);
    return rs.toString();
  }

  public NBModel buildModel(DataInfo dinfo, NBTask tsk) {
    return buildModel(dinfo, tsk, 0);
  }

  public NBModel buildModel(DataInfo dinfo, NBTask tsk, double laplace) {
    logStart();
    double[] pprior = tsk._rescnt.clone();
    double[][][] pcond = tsk._jntcnt.clone();
    String[][] domains = dinfo._adaptedFrame.domains();

    // A-priori probability of response y
    for(int i = 0; i < pprior.length; i++)
      pprior[i] = (pprior[i] + laplace)/(tsk._nobs + tsk._nres*laplace);
      // pprior[i] = pprior[i]/tsk._nobs;     // Note: R doesn't apply laplace smoothing to priors, even though this is textbook definition

    // Probability of categorical predictor x_j conditional on response y
    for(int col = 0; col < dinfo._cats; col++) {
      for(int i = 0; i < pcond[0].length; i++) {
        for(int j = 0; j < pcond[0][0].length; j++)
          pcond[col][i][j] = (pcond[col][i][j] + laplace)/(tsk._rescnt[i] + domains[col].length*laplace);
      }
    }

    // Mean and standard deviation of numeric predictor x_j for every level of response y
    for(int col = 0; col < dinfo._nums; col++) {
      for(int i = 0; i < pcond[0].length; i++) {
        int cidx = dinfo._cats + col;
        double num = tsk._rescnt[i];
        double pmean = pcond[cidx][i][0]/num;

        pcond[cidx][i][0] = pmean;
        // double pvar = pcond[cidx][i][1]/num - pmean*pmean;
        double pvar = pcond[cidx][i][1]/(num - 1) - pmean*pmean*num/(num - 1);
        pcond[cidx][i][1] = Math.sqrt(pvar);
      }
    }

    Key dataKey = input("source") == null ? null : Key.make(input("source"));
    return new NBModel(destination_key, dataKey, dinfo, tsk, pprior, pcond, laplace);
  }

  // Note: NA handling differs from R for efficiency purposes
  // R's method: For each predictor x_j, skip counting that row for p(x_j|y) calculation if x_j = NA. If response y = NA, skip counting row entirely in all calculations
  // H2O's method: Just skip all rows where any x_j = NA or y = NA. Should be more memory-efficient, but results incomparable with R.
  public static class NBTask extends MRTask2<NBTask> {
    final Job _job;
    final protected DataInfo _dinfo;
    final int _nres;              // Number of levels for the response y

    public int _nobs;             // Number of rows counted in calculation
    public double[] _rescnt;      // Count of each level in the response
    public double[][][] _jntcnt;  // For each categorical predictor, joint count of response and predictor levels
                                  // For each numeric predictor, sum of entries for every response level

    public NBTask(Job job, DataInfo dinfo) {
      _job = job;
      _dinfo = dinfo;
      _nobs = 0;

      String[][] domains = dinfo._adaptedFrame.domains();
      int ncol = dinfo._adaptedFrame.numCols();
      _nres = domains[ncol-1].length;

      _rescnt = new double[_nres];
      _jntcnt = new double[ncol-1][][];
      for(int i = 0; i < _jntcnt.length; i++) {
        int ncnt = domains[i] == null ? 2 : domains[i].length;
        _jntcnt[i] = new double[_nres][ncnt];
      }
    }

    @Override public void map(Chunk[] chks) {
      int res_idx = chks.length - 1;
      Chunk res = chks[res_idx];

      OUTER:
      for(int row = 0; row < chks[0]._len; row++) {
        // Skip row if any entries in it are NA
        for(int col = 0; col < chks.length; col++) {
          if(chks[col].isNA0(row)) continue OUTER;
        }

        // Record joint counts of categorical predictors and response
        int rlevel = (int)res.at0(row);
        for(int col = 0; col < _dinfo._cats; col++) {
          int plevel = (int)chks[col].at0(row);
          _jntcnt[col][rlevel][plevel]++;
        }

        // Record sum for each pair of numerical predictors and response
        for(int col = 0; col < _dinfo._nums; col++) {
          double x = chks[col + _dinfo._cats].at0(row);
          _jntcnt[col][rlevel][0] += x;
          _jntcnt[col][rlevel][1] += x*x;
        }
        _rescnt[rlevel]++;
        _nobs++;
      }
    }

    @Override public void reduce(NBTask nt) {
      _nobs += nt._nobs;
      Utils.add(_rescnt, nt._rescnt);
      for(int col = 0; col < _jntcnt.length; col++)
        _jntcnt[col] = Utils.add(_jntcnt[col], nt._jntcnt[col]);
    }
  }
}
